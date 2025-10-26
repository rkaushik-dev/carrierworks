"""Where jobs wait, and the rule about not running two on one board.

The store is a queue with one extra thing in it: a board can only be in one job
at a time. That sounds obvious and it is the invariant that a naive worker pool
breaks first, because two jobs for one board look independent right up until
they are both halfway through a flash erase.

Retries are held here as well. A job that failed transiently is queued again
with a time before which it will not be offered, so the runner does not have to
sleep and the backoff survives a restart of whatever is calling it.
"""

from __future__ import annotations

from datetime import datetime, timedelta

from fpga.errors import NotFound, ValidationError
from fpga.ids import IdFactory, slug
from fpga.jobs.models import ProgramJob
from fpga.records import board_key

__all__ = ["JobStore"]


class JobStore:
    """Every job the bench has been asked for, and what it is doing now."""

    def __init__(self, *, start: int = 1) -> None:
        self._jobs: dict[str, ProgramJob] = {}
        self._not_before: dict[str, datetime] = {}
        self._ids = IdFactory("job", start=start)

    def submit(
        self,
        board: str,
        digest: str,
        mode: str,
        requested_by: str,
        now: datetime,
        *,
        verify: bool = True,
    ) -> ProgramJob:
        job = ProgramJob(
            id=self._ids.next(),
            board=board,
            digest=digest,
            mode=mode,
            requested_by=requested_by,
            created_at=now,
            verify=verify,
        )
        self._jobs[job.id] = job
        return job

    def job(self, job_id: str) -> ProgramJob:
        found = self._jobs.get(slug(job_id))
        if found is None:
            raise NotFound("job", slug(job_id))
        return found

    def update(self, job: ProgramJob) -> ProgramJob:
        """Write a job back after the runner has moved it along."""
        if job.id not in self._jobs:
            raise NotFound("job", job.id)
        self._jobs[job.id] = job
        if job.finished:
            self._not_before.pop(job.id, None)
        return job

    def hold_until(self, job_id: str, when: datetime) -> None:
        """Keep a job off the ready list until a time, which is the backoff."""
        self.job(job_id)
        self._not_before[slug(job_id)] = when

    def held_until(self, job_id: str) -> datetime | None:
        return self._not_before.get(slug(job_id))

    def cancel(self, job_id: str, now: datetime, reason: str = "cancelled") -> ProgramJob:
        cancelled = self.job(job_id).cancel(now, reason)
        return self.update(cancelled)

    def all(self) -> tuple[ProgramJob, ...]:
        return tuple(sorted(self._jobs.values(), key=lambda job: job.id))

    def in_state(self, state: str) -> tuple[ProgramJob, ...]:
        return tuple(job for job in self.all() if job.state == state)

    def queued(self) -> tuple[ProgramJob, ...]:
        return self.in_state("queued")

    def running(self) -> tuple[ProgramJob, ...]:
        return self.in_state("running")

    def for_board(self, board: str) -> tuple[ProgramJob, ...]:
        tag = board_key(board)
        return tuple(job for job in self.all() if job.board == tag)

    def busy_boards(self) -> tuple[str, ...]:
        return tuple(sorted({job.board for job in self.running()}))

    def ready(self, now: datetime) -> tuple[ProgramJob, ...]:
        """Jobs that could start right now, oldest first.

        A job is ready when it is queued, its backoff has passed, and nothing
        else is running on its board. Two queued jobs for one board means the
        older one is offered and the other waits, which is the whole point.
        """
        busy = set(self.busy_boards())
        offered: list[ProgramJob] = []
        for job in sorted(self.queued(), key=lambda job: (job.created_at, job.id)):
            hold = self._not_before.get(job.id)
            if hold is not None and now < hold:
                continue
            if job.board in busy:
                continue
            busy.add(job.board)
            offered.append(job)
        return tuple(offered)

    def next_ready(self, now: datetime) -> ProgramJob | None:
        ready = self.ready(now)
        return ready[0] if ready else None

    def waiting_for_backoff(self, now: datetime) -> tuple[ProgramJob, ...]:
        return tuple(
            job
            for job in self.queued()
            if (hold := self._not_before.get(job.id)) is not None and now < hold
        )

    def last_for_board(self, board: str) -> ProgramJob | None:
        found = self.for_board(board)
        return found[-1] if found else None

    def abandon_stale(self, now: datetime, older_than: timedelta) -> tuple[ProgramJob, ...]:
        """Fail any job that has been running longer than it possibly should.

        This is the safety net for a worker that died without writing
        anything: the job sits in running forever and its board is never
        offered again. Anything past the limit is closed and named.
        """
        if older_than <= timedelta(0):
            raise ValidationError("a limit has some length", field="older_than")
        closed = []
        for job in self.running():
            started = job.started_at
            if started is not None and now - started > older_than:
                dead = job.failed(now, "the worker running this job went away", code="abandoned")
                closed.append(self.update(dead))
        return tuple(closed)

    def __len__(self) -> int:
        return len(self._jobs)
