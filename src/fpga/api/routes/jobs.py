"""The job routes: what has been asked for and what is next to run."""

from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, Query

from fpga.access import ApiKey
from fpga.api.deps import now, unit_of_work
from fpga.api.schemas import JobIn, JobOut
from fpga.api.security import needs
from fpga.persistence.unit import UnitOfWork
from fpga.services import programming

router = APIRouter(tags=["jobs"])


@router.get("/jobs", response_model=list[JobOut])
def list_jobs(
    unit: UnitOfWork = Depends(unit_of_work),
    state: str | None = Query(default=None, description="queued, running, passed, failed"),
    digest: str | None = Query(default=None, description="one image"),
    _: ApiKey = Depends(needs("reader")),
) -> list[JobOut]:
    """Jobs, narrowed by state or by image."""
    if digest is not None:
        found = unit.jobs.for_digest(digest)
    elif state == "running":
        found = unit.jobs.running()
    else:
        found = tuple(
            job
            for board in unit.boards.all()
            for job in unit.jobs.for_board(board.tag)
            if state is None or job.state == state
        )
    return [JobOut.of(job) for job in found]


@router.get("/jobs/ready", response_model=list[JobOut])
def list_ready(
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    _: ApiKey = Depends(needs("operator")),
) -> list[JobOut]:
    """The jobs a worker could start right now, oldest first."""
    return [JobOut.of(job) for job in programming.ready(unit, moment)]


@router.get("/jobs/{job_id}", response_model=JobOut)
def read_job(
    job_id: str,
    unit: UnitOfWork = Depends(unit_of_work),
    _: ApiKey = Depends(needs("reader")),
) -> JobOut:
    """One job."""
    return JobOut.of(unit.jobs.get(job_id))


@router.post("/jobs", response_model=JobOut, status_code=201)
def submit_job(
    asked: JobIn,
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    _: ApiKey = Depends(needs("operator")),
) -> JobOut:
    """Ask for a board to be programmed."""
    job = programming.submit(
        unit,
        asked.board,
        asked.digest,
        asked.mode,
        asked.requested_by,
        moment,
        verify=asked.verify,
    )
    return JobOut.of(job)


@router.post("/jobs/{job_id}/cancel", response_model=JobOut)
def cancel_job(
    job_id: str,
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    reason: str = Query(default="cancelled", description="why it is being dropped"),
    _: ApiKey = Depends(needs("operator")),
) -> JobOut:
    """Take a job off the queue."""
    return JobOut.of(programming.cancel(unit, job_id, moment, reason))


@router.get("/boards/{tag}/jobs", response_model=list[JobOut])
def board_jobs(
    tag: str,
    unit: UnitOfWork = Depends(unit_of_work),
    _: ApiKey = Depends(needs("reader")),
) -> list[JobOut]:
    """Everything that has been asked of one board."""
    unit.boards.get(tag)
    return [JobOut.of(job) for job in programming.for_board(unit, tag)]
