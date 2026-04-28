"""Picking the bench up after something died in the middle of a job.

Two sources are read and they disagree in an informative way. The journal knows
which jobs were started and never closed, because it is written before each
step. The board book knows which boards were left in the programming state,
because that is written when a job starts. A board in the second list without a
job in the first is a board somebody moved by hand; a job in the first without
a board in the second is a job whose board has since been reset.

What happens to a board depends on what was being done to it. A JTAG load that
was interrupted leaves a device in an unknown but harmless state: the next load
clears it, so the board goes back to service. A flash write that was
interrupted leaves a part that may be half erased, and that board is faulted
until somebody has looked at it, because the next boot from it is the one that
matters.

Recovery is idempotent. Running it twice finds nothing the second time, which
matters because it runs at startup and startups happen in threes when something
is really wrong.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from fpga.jobs.journal import JobJournal
from fpga.registries import BoardRegistry, JobLedger, LeaseRegistry

__all__ = [
    "RecoveryReport",
    "recover",
]


@dataclass(frozen=True, slots=True)
class RecoveryReport:
    """What the bench found and did when it came back."""

    jobs_closed: tuple[str, ...] = ()
    jobs_requeued: tuple[str, ...] = ()
    boards_faulted: tuple[str, ...] = ()
    boards_returned: tuple[str, ...] = ()
    unknown_jobs: tuple[str, ...] = ()

    @property
    def clean(self) -> bool:
        return not (
            self.jobs_closed or self.jobs_requeued or self.boards_faulted or self.boards_returned
        )

    def describe(self) -> str:
        if self.clean:
            return "nothing was left half done"
        parts = []
        if self.jobs_closed:
            parts.append(f"closed {', '.join(self.jobs_closed)}")
        if self.jobs_requeued:
            parts.append(f"requeued {', '.join(self.jobs_requeued)}")
        if self.boards_faulted:
            parts.append(f"faulted {', '.join(self.boards_faulted)}")
        if self.boards_returned:
            parts.append(f"returned {', '.join(self.boards_returned)}")
        if self.unknown_jobs:
            parts.append(f"journal mentions unknown jobs {', '.join(self.unknown_jobs)}")
        return "; ".join(parts)


def recover(
    journal: JobJournal,
    jobs: JobLedger,
    book: BoardRegistry,
    leases: LeaseRegistry,
    now: datetime,
    *,
    retry_limit: int = 0,
) -> RecoveryReport:
    """Close what was left open and put the boards somewhere honest.

    A retry limit puts an interrupted load back on the queue rather than
    failing it, as long as it has attempts left and it was not writing flash.
    A flash write is never retried on its own, because the part it was writing
    is in a state nothing can read back and be sure of. Zero, the default,
    means every interrupted job ends.
    """
    closed: list[str] = []
    requeued: list[str] = []
    faulted: list[str] = []
    returned: list[str] = []
    unknown: list[str] = []
    boards_seen: set[str] = set()

    for job_id in journal.unfinished():
        try:
            job = jobs.job(job_id)
        except Exception:  # noqa: BLE001 - a journal can outlive a job store
            unknown.append(job_id)
            journal.append(job_id, "abandoned", now, reason="job record is gone")
            continue
        boards_seen.add(job.board)
        if not job.finished:
            running = job if job.state == "running" else job.start(now)
            retryable = retry_limit > 0 and not job.writes_flash and not job.exhausted(retry_limit)
            jobs.update(
                running.failed(
                    now,
                    "the bench restarted during this job",
                    code="abandoned",
                    retryable=retryable,
                )
            )
            (requeued if retryable else closed).append(job.id)
        journal.append(job.id, "abandoned", now, board=job.board)
        _settle(
            book,
            leases,
            job.board,
            now,
            writes_flash=job.writes_flash,
            faulted=faulted,
            returned=returned,
        )

    for board in book.in_state("programming"):
        if board.tag in boards_seen:
            continue
        _settle(
            book,
            leases,
            board.tag,
            now,
            writes_flash=False,
            faulted=faulted,
            returned=returned,
        )

    return RecoveryReport(
        jobs_closed=tuple(closed),
        jobs_requeued=tuple(requeued),
        boards_faulted=tuple(sorted(set(faulted))),
        boards_returned=tuple(sorted(set(returned))),
        unknown_jobs=tuple(unknown),
    )


def _settle(
    book: BoardRegistry,
    leases: LeaseRegistry,
    board: str,
    now: datetime,
    *,
    writes_flash: bool,
    faulted: list[str],
    returned: list[str],
) -> None:
    """Move one board out of the state a dead job left it in."""
    if not book.has(board):
        return
    state = book.state(board).state
    if state != "programming":
        return
    if writes_flash:
        book.move_to(board, "leased", now, actor="recovery")
        book.move_to(
            board,
            "faulted",
            now,
            reason="a flash write was interrupted by a restart",
            actor="recovery",
        )
        held = leases.for_board(board, now)
        if held is not None:
            leases.release(held.id, now, "board faulted during recovery")
        faulted.append(board)
        return
    book.move_to(board, "leased", now, actor="recovery")
    if leases.for_board(board, now) is None:
        book.move_to(board, "available", now, actor="recovery")
    returned.append(board)
