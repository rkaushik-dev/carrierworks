"""One answer to what the bench is doing, assembled from every other part.

This exists because the question gets asked constantly and answering it used
to mean four commands and some arithmetic: how many boards are free, who is
waiting, is anything too hot, is anything out of calibration. None of that is
new information. What is new is that it is one read, one transaction, and one
shape that both the command line and the status page render.

The counts are deliberately shallow. Anything that needs a per board answer
belongs in that board's own command, and a roll up that tries to be both ends
up being neither.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta

from fpga.config import BenchSettings
from fpga.persistence.unit import UnitOfWork
from fpga.services import thermal as thermal_service
from fpga.services import upkeep

__all__ = [
    "BenchOverview",
    "overview",
]


@dataclass(frozen=True, slots=True)
class BenchOverview:
    """Where the whole bench stands at one moment."""

    at: datetime
    site: str
    boards: dict[str, int] = field(default_factory=dict)
    leases_held: int = 0
    leases_expiring: int = 0
    queue_waiting: int = 0
    queue_longest_wait_seconds: int = 0
    jobs_queued: int = 0
    jobs_running: int = 0
    thermal: dict[str, int] = field(default_factory=dict)
    calibration_overdue: tuple[str, ...] = ()

    @property
    def available(self) -> int:
        return self.boards.get("available", 0)

    @property
    def total_boards(self) -> int:
        return sum(self.boards.values())

    @property
    def healthy(self) -> bool:
        """Nothing on fire: no alarms, nothing faulted, nothing out of date."""
        alarms = sum(count for state, count in self.thermal.items() if state != "normal")
        return alarms == 0 and self.boards.get("faulted", 0) == 0 and not self.calibration_overdue

    def describe(self) -> str:
        line = (
            f"{self.site}: {self.available} of {self.total_boards} free, "
            f"{self.leases_held} held, {self.queue_waiting} waiting, "
            f"{self.jobs_queued} jobs queued"
        )
        if self.calibration_overdue:
            line = f"{line}, {len(self.calibration_overdue)} out of calibration"
        return line


def _tally(values: list[str]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for value in values:
        counts[value] = counts.get(value, 0) + 1
    return dict(sorted(counts.items()))


def overview(
    unit: UnitOfWork,
    now: datetime,
    *,
    settings: BenchSettings | None = None,
    expiring_within: timedelta = timedelta(minutes=15),
) -> BenchOverview:
    """Everything a status page shows, in one pass over the database."""
    live = settings or BenchSettings()
    boards = unit.boards.all()
    states = _tally([unit.boards.state(board.tag).state for board in boards])
    held = unit.leases.active(now)
    waiting = unit.requests.ordered(now)
    jobs = [job for board in boards for job in unit.jobs.for_board(board.tag)]
    thermal = _tally(
        [status.state for status in thermal_service.status_of(unit, now, settings=live)]
    )
    return BenchOverview(
        at=now,
        site=live.site,
        boards=states,
        leases_held=len(held),
        leases_expiring=len([lease for lease in held if lease.expires_at <= now + expiring_within]),
        queue_waiting=len(waiting),
        queue_longest_wait_seconds=(
            int(max(request.waited(now) for request in waiting).total_seconds()) if waiting else 0
        ),
        jobs_queued=len([job for job in jobs if job.state == "queued"]),
        jobs_running=len([job for job in jobs if job.state == "running"]),
        thermal=thermal,
        calibration_overdue=tuple(state.board for state in upkeep.overdue(unit, now)),
    )
