"""Asking for a board to be programmed, and finding the next job to run.

The queue lives in the database rather than in a worker's memory, so a job
submitted while nothing is running is still there when a worker starts, and a
worker that dies takes nothing with it.

One job at a time per board is enforced here rather than in the runner. By the
time the runner has a job it is already committed to touching that board, and
the check belongs where the decision is made.
"""

from __future__ import annotations

from datetime import datetime

from fpga.errors import Conflict, ValidationError
from fpga.jobs.models import MODES, ProgramJob
from fpga.persistence.unit import UnitOfWork
from fpga.records import board_key

__all__ = [
    "cancel",
    "next_job_id",
    "ready",
    "submit",
]

_PREFIX = "job"
_WIDTH = 5


def next_job_id(unit: UnitOfWork) -> str:
    """The next job number, carried on from what is already stored."""
    highest = 0
    for board in unit.boards.all():
        for job in unit.jobs.for_board(board.tag):
            tail = job.id.rsplit("-", 1)[-1]
            if tail.isdigit():
                highest = max(highest, int(tail))
    return f"{_PREFIX}-{highest + 1:0{_WIDTH}d}"


def submit(
    unit: UnitOfWork,
    tag: str,
    digest: str,
    mode: str,
    requested_by: str,
    now: datetime,
    *,
    verify: bool = True,
) -> ProgramJob:
    """Put a programming job on the queue."""
    if mode not in MODES:
        raise ValidationError("no such programming mode", field="mode", given=mode)
    board = unit.boards.get(tag)
    unit.artifacts.get(digest)
    for existing in unit.jobs.for_board(board.tag):
        if existing.state == "running":
            raise Conflict(
                "that board is already being programmed",
                board=board.tag,
                job=existing.id,
            )
    job = ProgramJob(
        id=next_job_id(unit),
        board=board.tag,
        digest=digest,
        mode=mode,
        requested_by=requested_by,
        created_at=now,
        verify=verify,
    )
    unit.jobs.add(job)
    return job


def ready(unit: UnitOfWork, now: datetime) -> tuple[ProgramJob, ...]:
    """Jobs that could start now, oldest first, one per board.

    A board with a running job is skipped, and so is the second queued job for
    a board whose first is being offered in this same pass. Without that, two
    workers pulling at once would both take a job for one board.
    """
    busy = {job.board for job in unit.jobs.running()}
    offered: list[ProgramJob] = []
    for job in unit.jobs.queued(now):
        if job.board in busy:
            continue
        busy.add(job.board)
        offered.append(job)
    return tuple(offered)


def next_ready(unit: UnitOfWork, now: datetime) -> ProgramJob | None:
    found = ready(unit, now)
    return found[0] if found else None


def cancel(unit: UnitOfWork, job_id: str, now: datetime, reason: str = "cancelled") -> ProgramJob:
    """Take a job off the queue."""
    cancelled = unit.jobs.get(job_id).cancel(now, reason)
    return unit.jobs.save(cancelled)


def for_board(unit: UnitOfWork, tag: str) -> tuple[ProgramJob, ...]:
    return unit.jobs.for_board(board_key(tag))


def hold(unit: UnitOfWork, job: ProgramJob, until: datetime) -> ProgramJob:
    """Keep a retried job off the ready list until its backoff has passed."""
    return unit.jobs.save(job, not_before=until)
