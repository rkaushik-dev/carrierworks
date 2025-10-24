"""Running a programming job: gates, attempt, journal, and what to do after.

The runner is the only place in the bench that knows the whole sequence, and it
is written so that every path through it leaves three things behind: a journal
line, a job record with its state, and a board in a state somebody can act on.

Retries are the part worth reading. A transient failure puts the job back on
the queue and records a fault against the board, so a board that keeps
producing transient failures ends up quarantined by the fault policy rather
than by anything here. A permanent failure ends the job. And a failure while
the board is in an unknown state, which is anything that goes wrong during a
flash write, faults the board as well, because a half written flash is not a
board to hand to the next person.

The split between the two halves of the flash is the lab's convention and it
lives here rather than on the board: the golden image gets the bottom half of
whatever part is fitted, the update image gets the top half, and the warm boot
address is therefore known without anybody writing it down twice.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime

from fpga.artifacts.registry import ArtifactRecord, ArtifactStore
from fpga.catalog.flashparts import FlashSpec
from fpga.config import BenchSettings
from fpga.errors import BenchError, NotFound, TransientBenchError
from fpga.flash.layout import FlashLayout, multiboot_layout
from fpga.flash.plan import plan_write
from fpga.flash.wear import WearLedger
from fpga.inventory.book import BoardBook
from fpga.inventory.faults import FaultLog
from fpga.jobs.flasher import SpiFlashDriver, write_image
from fpga.jobs.gates import check_job
from fpga.jobs.journal import JobJournal
from fpga.jobs.models import ProgramJob
from fpga.jobs.programmer import load_over_jtag
from fpga.jtag.session import ScanSession
from fpga.leases.store import LeaseStore

__all__ = [
    "JobRunner",
    "RunResult",
    "flash_layout",
]


def flash_layout(spec: FlashSpec) -> FlashLayout:
    """Golden in the bottom half of the part, update in the top half."""
    half = spec.capacity_bytes // 2
    return multiboot_layout(spec, half, half, update_start=half)


@dataclass(frozen=True, slots=True)
class RunResult:
    """The job after an attempt, and what the bench did about it."""

    job: ProgramJob
    detail: str
    faulted_board: bool = False
    retry_in_seconds: int = 0

    @property
    def passed(self) -> bool:
        return self.job.succeeded

    @property
    def will_retry(self) -> bool:
        return self.job.state == "queued"

    def describe(self) -> str:
        line = f"{self.job.id} {self.job.state}: {self.detail}"
        if self.will_retry:
            return f"{line}, retry in {self.retry_in_seconds}s"
        return line


class JobRunner:
    """Runs one job at a time against whatever the bench hands it."""

    def __init__(
        self,
        store: ArtifactStore,
        book: BoardBook,
        leases: LeaseStore,
        journal: JobJournal,
        faults: FaultLog,
        *,
        settings: BenchSettings | None = None,
        wear: WearLedger | None = None,
    ) -> None:
        self.store = store
        self.book = book
        self.leases = leases
        self.journal = journal
        self.faults = faults
        self.settings = settings or BenchSettings()
        self.wear = wear

    def backoff_seconds(self, attempt: int) -> int:
        """How long to wait before the next attempt, growing with each one."""
        return self.settings.program_backoff_seconds * max(attempt, 1)

    def run(
        self,
        job: ProgramJob,
        now: datetime,
        *,
        session_for: Callable[[str], ScanSession] | None = None,
        flash_for: Callable[[str], SpiFlashDriver] | None = None,
        timing_closed: bool | None = None,
    ) -> RunResult:
        """One attempt at one job."""
        board = self.book.board(job.board)
        record = self.store.record_for(job.digest)
        report = check_job(
            job,
            board=board,
            record=record,
            state=self.book.state(job.board),
            lease=self.leases.for_board(job.board, now),
            timing_closed=timing_closed,
            require_timing=self.settings.require_timing_closure,
            wear=self.wear,
        )
        if not report.allowed:
            reason = "; ".join(report.blockers)
            self.journal.append(job.id, "failed", now, board=job.board, reason=reason)
            return RunResult(job=job.start(now).failed(now, reason, code="gate"), detail=reason)
        return self._attempt(
            job,
            record,
            now,
            session_for=session_for,
            flash_for=flash_for,
        )

    def _attempt(
        self,
        job: ProgramJob,
        record: ArtifactRecord,
        now: datetime,
        *,
        session_for: Callable[[str], ScanSession] | None,
        flash_for: Callable[[str], SpiFlashDriver] | None,
    ) -> RunResult:
        lease = self.leases.for_board(job.board, now)
        running = job.start(now, lease="" if lease is None else lease.id)
        self.journal.append(running.id, "started", now, board=running.board, mode=running.mode)
        self.book.move_to(running.board, "programming", now, actor=running.requested_by)
        image = self.store.read(record.digest)
        try:
            detail = self._carry_out(running, image, session_for=session_for, flash_for=flash_for)
        except BenchError as failure:
            return self._failed(running, failure, now)
        self.journal.append(running.id, "passed", now, board=running.board)
        self.book.move_to(running.board, "leased", now, actor=running.requested_by)
        return RunResult(job=running.passed(now), detail=detail)

    def _carry_out(
        self,
        job: ProgramJob,
        image: bytes,
        *,
        session_for: Callable[[str], ScanSession] | None,
        flash_for: Callable[[str], SpiFlashDriver] | None,
    ) -> str:
        board = self.book.board(job.board)
        if job.mode == "jtag":
            if session_for is None:
                raise NotFound("transport", job.board)
            session = session_for(job.board)
            outcome = load_over_jtag(session, image, board=job.board)
            return outcome.describe()
        if flash_for is None:
            raise NotFound("flash driver", job.board)
        spec = board.flash_spec()
        layout = flash_layout(spec)
        region = "golden" if job.touches_golden else "update"
        plan = plan_write(layout, region, image, verify=job.verify)
        if self.wear is not None:
            self.wear.record(job.board, plan)
        written = write_image(
            flash_for(job.board),
            plan,
            image,
            start=layout.region(region).start,
        )
        return written.describe()

    def _failed(self, job: ProgramJob, failure: BenchError, now: datetime) -> RunResult:
        retryable = isinstance(failure, TransientBenchError) and not job.exhausted(
            self.settings.program_attempts
        )
        self.journal.append(
            job.id,
            "failed",
            now,
            board=job.board,
            reason=failure.message,
            code=failure.code,
        )
        self.faults.add(
            job.board,
            _fault_kind(failure.code),
            now,
            detail=failure.message,
            job=job.id,
        )
        unknown = job.writes_flash
        if unknown:
            self.book.move_to(
                job.board,
                "faulted",
                now,
                reason=f"{job.mode} failed part way: {failure.message}",
                actor="runner",
            )
        else:
            self.book.move_to(job.board, "leased", now, actor=job.requested_by)
        beaten = job.failed(now, failure.message, code=failure.code, retryable=retryable)
        return RunResult(
            job=beaten,
            detail=failure.message,
            faulted_board=unknown,
            retry_in_seconds=self.backoff_seconds(job.attempts) if retryable else 0,
        )


def _fault_kind(code: str) -> str:
    """Map an error code onto the fault kinds the quarantine policy counts."""
    if code in ("chain", "programming"):
        return "chain" if code == "chain" else "program"
    if code == "verify":
        return "verify"
    if code == "power":
        return "power"
    return "other"
