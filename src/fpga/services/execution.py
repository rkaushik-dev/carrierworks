"""Wiring a job to a board and running it, in one place instead of two.

The runner takes five collaborators and none of them are optional, which is
right for the runner and wrong for every caller: the command line and the
worker were assembling the same five the same way, and the second one to be
written quietly forgot to pass the timing report, so jobs that should have
been gated on timing closure were not.

So the assembly lives here now, once. What a caller chooses is whether to use
the real cable or the simulator, and everything else follows from the job and
the settings.
"""

from __future__ import annotations

from datetime import datetime

from fpga.artifacts.registry import ArtifactStore
from fpga.config import BenchSettings
from fpga.drivers import flash_for, session_for
from fpga.inventory.faults import FaultLog
from fpga.jobs.journal import JobJournal
from fpga.jobs.models import ProgramJob
from fpga.jobs.runner import JobRunner, RunResult
from fpga.persistence.unit import UnitOfWork
from fpga.services import programming, reports
from fpga.services.adapters import BookOverRepository, LeaseStoreOverRepository

__all__ = [
    "journal_for",
    "next_ready",
    "run_job",
]


def journal_for(settings: BenchSettings) -> JobJournal:
    """The one journal file the whole bench appends to."""
    return JobJournal(settings.journal_root / "jobs.jsonl")


def next_ready(unit: UnitOfWork, now: datetime) -> ProgramJob | None:
    """The next job worth attempting, or nothing when the queue is quiet."""
    return programming.next_ready(unit, now)


def run_job(
    unit: UnitOfWork,
    job: ProgramJob,
    now: datetime,
    *,
    settings: BenchSettings | None = None,
    journal: JobJournal | None = None,
    faults: FaultLog | None = None,
    live: bool = False,
) -> RunResult:
    """Run one job against a board, and save whatever it came to.

    The board is read once and handed to both factories, so a job whose board
    row changes underneath it still talks to the board it was gated against.
    """
    live_settings = settings or BenchSettings()
    board = unit.boards.get(job.board)
    closed, _ = reports.timing_for(unit, job.digest)
    runner = JobRunner(
        ArtifactStore(live_settings.artifact_root),
        BookOverRepository(unit),
        LeaseStoreOverRepository(unit),
        journal or journal_for(live_settings),
        faults or FaultLog(),
        settings=live_settings,
    )
    result = runner.run(
        job,
        now,
        session_for=lambda _: session_for(board, simulated=not live),
        flash_for=lambda _: flash_for(board, simulated=not live),
        timing_closed=closed,
    )
    unit.jobs.save(result.job, not_before=now if result.will_retry else None)
    return result
