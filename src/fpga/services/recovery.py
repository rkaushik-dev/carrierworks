"""Picking the bench back up after a worker died, against the database.

The rules for what to do about an interrupted job are not here. They are in
`fpga.jobs.recovery`, written against the in memory bench, and they were
duplicated here for exactly as long as it took to notice that the two copies
had already drifted: this one requeued an interrupted jtag load and that one
failed it, so what happened to a job depended on which entry point somebody
had used.

What is left here is the part that genuinely differs: the adapters that let
those rules read and write the database, the retry limit out of the settings,
and the audit lines, which the in memory bench has nowhere to write.
"""

from __future__ import annotations

from datetime import datetime

from fpga.audit import AuditEntry
from fpga.config import BenchSettings
from fpga.jobs.journal import JobJournal
from fpga.jobs.models import ProgramJob
from fpga.jobs.recovery import RecoveryReport
from fpga.jobs.recovery import recover as recover_bench
from fpga.persistence.unit import UnitOfWork
from fpga.services.adapters import (
    BookOverRepository,
    JobLedgerOverRepository,
    LeaseStoreOverRepository,
)

__all__ = [
    "interrupted",
    "recover",
]


def interrupted(unit: UnitOfWork, journal: JobJournal) -> tuple[ProgramJob, ...]:
    """Every job the database calls running that the journal never closed.

    A job the journal has never heard of counts as interrupted too. That is
    the crash between writing the row and writing the first journal line, and
    it is rare, but the answer to it is the same.
    """
    open_ids = set(journal.unfinished())
    seen = {entry.job for entry in journal.entries()}
    return tuple(job for job in unit.jobs.running() if job.id in open_ids or job.id not in seen)


def _note(unit: UnitOfWork, now: datetime, action: str, subject: str, detail: str) -> None:
    unit.audit.add(
        AuditEntry(
            at=now,
            actor="recovery",
            action=action,
            subject=subject,
            detail=detail[:240],
        )
    )


def recover(
    unit: UnitOfWork,
    journal: JobJournal,
    now: datetime,
    *,
    settings: BenchSettings | None = None,
) -> RecoveryReport:
    """Close out every interrupted job, and write down what that cost.

    Safe to run at any time, and meant to run at start up. A job that is
    genuinely in flight looks exactly like one that was abandoned, so the
    caller is the thing that knows nothing else is running.
    """
    live = settings or BenchSettings()
    for job in interrupted(unit, journal):
        if job.id not in set(journal.unfinished()):
            journal.append(job.id, "started", now, board=job.board, mode=job.mode)
    report = recover_bench(
        journal,
        JobLedgerOverRepository(unit),
        BookOverRepository(unit),
        LeaseStoreOverRepository(unit),
        now,
        retry_limit=live.program_attempts,
    )
    for board in report.boards_faulted:
        _note(unit, now, "faulted", board, "a flash write was interrupted by a restart")
    for board in report.boards_returned:
        _note(unit, now, "returned", board, "put back after an interrupted load")
    for job_id in report.jobs_requeued:
        _note(unit, now, "requeued", job_id, "interrupted part way through a load")
    for job_id in report.jobs_closed:
        _note(unit, now, "closed", job_id, "the bench restarted during this job")
    return report
