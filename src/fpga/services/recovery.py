"""Picking the bench back up after a worker died in the middle of something.

A job row that says running and a process that no longer exists mean the same
thing, and the database cannot tell them apart on its own. The journal can:
every step is written before it is attempted, so a job with an opening line and
no closing one was in flight when the lights went out.

What to do about it depends entirely on the mode. A jtag load that was
interrupted left a part with a half loaded configuration, which the next load
overwrites, so the job goes back on the queue and the board is fine. A flash
write that was interrupted left a part with some sectors erased and some not,
which nothing detects and every later boot trusts, so the board is faulted and
somebody has to look at it.

That asymmetry is the whole module. Everything else here is bookkeeping.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from fpga.audit import AuditEntry
from fpga.config import BenchSettings
from fpga.jobs.journal import JobJournal
from fpga.jobs.models import ProgramJob
from fpga.persistence.unit import UnitOfWork
from fpga.services import inventory

__all__ = [
    "Recovery",
    "interrupted",
    "recover",
]


@dataclass(frozen=True, slots=True)
class Recovery:
    """What was done about one interrupted job."""

    job: str
    board: str
    mode: str
    action: str
    reason: str

    @property
    def board_faulted(self) -> bool:
        return self.action == "faulted"

    def describe(self) -> str:
        return f"{self.job} on {self.board}: {self.action}, {self.reason}"


def interrupted(unit: UnitOfWork, journal: JobJournal) -> tuple[ProgramJob, ...]:
    """Every job the database calls running that the journal never closed.

    A job the journal has never heard of counts as interrupted too. That is
    the crash between writing the row and writing the first journal line, and
    it is rare, but the answer to it is the same.
    """
    open_ids = set(journal.unfinished())
    seen = {entry.job for entry in journal.entries()}
    return tuple(job for job in unit.jobs.running() if job.id in open_ids or job.id not in seen)


def _settle(unit: UnitOfWork, job: ProgramJob, now: datetime, reason: str) -> None:
    """Put a board back where an interrupted load found it.

    Where that is depends on the lease rather than on the job: a board whose
    lease outlived the crash goes back to its holder, and one whose lease has
    since been given back or swept goes back to the pool. A board already in
    the right state is left alone, because a state machine that refuses a move
    to where it already is would turn a second recovery pass into an error.
    """
    target = "leased" if unit.leases.for_board(job.board, now) is not None else "available"
    if unit.boards.state(job.board).state == target:
        return
    inventory.move_state(unit, job.board, target, now, reason=reason, actor="recovery")


def _requeue(unit: UnitOfWork, job: ProgramJob, now: datetime, reason: str) -> Recovery:
    unit.jobs.save(
        job.failed(now, reason, code="interrupted", retryable=True),
        not_before=now,
    )
    _settle(unit, job, now, reason)
    return Recovery(
        job=job.id,
        board=job.board,
        mode=job.mode,
        action="requeued",
        reason=reason,
    )


def _fault(unit: UnitOfWork, job: ProgramJob, now: datetime, reason: str) -> Recovery:
    unit.jobs.save(job.failed(now, reason, code="interrupted", retryable=False))
    inventory.fault_board(unit, job.board, now, reason, actor="recovery")
    return Recovery(
        job=job.id,
        board=job.board,
        mode=job.mode,
        action="faulted",
        reason=reason,
    )


def recover(
    unit: UnitOfWork,
    journal: JobJournal,
    now: datetime,
    *,
    settings: BenchSettings | None = None,
) -> tuple[Recovery, ...]:
    """Close out every interrupted job, and say what that cost.

    Runs at start up, and is safe to run at any other time: a job that is
    genuinely in flight has an open journal line too, so the only way to tell
    is that nothing else is meant to be running when this is called.
    """
    live = settings or BenchSettings()
    done: list[Recovery] = []
    for job in interrupted(unit, journal):
        exhausted = job.exhausted(live.program_attempts)
        if job.writes_flash:
            outcome = _fault(
                unit,
                job,
                now,
                "a flash write was interrupted, so the part is in an unknown state",
            )
        elif exhausted:
            outcome = Recovery(
                job=job.id,
                board=job.board,
                mode=job.mode,
                action="failed",
                reason=f"interrupted after {job.attempts} attempts",
            )
            unit.jobs.save(job.failed(now, outcome.reason, code="interrupted", retryable=False))
            _settle(unit, job, now, outcome.reason)
        else:
            outcome = _requeue(unit, job, now, "interrupted part way through a load")
        journal.append(job.id, "abandoned", now, board=job.board, reason=outcome.reason)
        unit.audit.add(
            AuditEntry(
                at=now,
                actor="recovery",
                action=outcome.action,
                subject=job.board,
                detail=f"{job.id}: {outcome.reason}"[:240],
            )
        )
        done.append(outcome)
    return tuple(done)
