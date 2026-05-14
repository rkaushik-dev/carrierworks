"""Who used what, for how long, and which boards nobody wanted.

The numbers here settle two arguments that come up every quarter. One team
thinks another is monopolising the Kintex boards, and the lab is asked to
justify keeping hardware that might be idle. Both are answerable from the
leases already stored, and neither is answerable from memory.

The window is closed at both ends and every lease that overlaps it counts for
the part that overlaps, so a lease that ran across midnight is not counted
twice and not dropped. That is the only arithmetic in here that is easy to get
wrong, and it lives in the scheduling metrics rather than in this module.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from fpga.errors import ValidationError
from fpga.leases.models import Lease
from fpga.persistence.unit import UnitOfWork
from fpga.scheduling.metrics import holder_hours, idle_boards, utilisation

__all__ = [
    "UsageReport",
    "leases_in",
    "usage",
]


@dataclass(frozen=True, slots=True)
class UsageReport:
    """What the bench did over one window."""

    start: datetime
    end: datetime
    boards: tuple[str, ...]
    utilisation: dict[str, float]
    holder_hours: dict[str, float]
    idle: tuple[str, ...]
    leases: int
    waiting: int

    @property
    def hours(self) -> float:
        return (self.end - self.start).total_seconds() / 3600

    @property
    def busiest(self) -> str | None:
        """The board held longest, or nothing when none of them were held.

        A bench where nothing ran has no busiest board, and answering with
        whichever board sorted first would read as a real number to anybody
        skimming the report.
        """
        if not self.utilisation:
            return None
        found = max(self.utilisation, key=lambda board: self.utilisation[board])
        return found if self.utilisation[found] > 0 else None

    def describe(self) -> str:
        days = round(self.hours / 24, 1)
        busiest = self.busiest
        line = f"{len(self.boards)} boards over {days} days, {self.leases} leases"
        if busiest is not None:
            line = f"{line}, busiest {busiest} at {self.utilisation[busiest] * 100:.0f}%"
        if self.idle:
            line = f"{line}, idle: {', '.join(self.idle)}"
        return line


def leases_in(unit: UnitOfWork, start: datetime, end: datetime) -> tuple[Lease, ...]:
    """Every lease that overlapped the window, live ones included.

    Read per board rather than by date, because the lease history is indexed
    by board and a window query would scan the whole table for what is nearly
    always a handful of rows.
    """
    found: list[Lease] = []
    for board in unit.boards.all():
        for lease in unit.leases.history(board.tag):
            finished = lease.released_at or lease.expires_at
            if lease.taken_at < end and finished > start:
                found.append(lease)
    return tuple(found)


def usage(
    unit: UnitOfWork,
    now: datetime,
    *,
    window: timedelta = timedelta(days=7),
) -> UsageReport:
    """Utilisation, board hours by team, and the boards nobody touched."""
    if window <= timedelta(0):
        raise ValidationError("a usage window has some length", field="window")
    start = now - window
    boards = tuple(board.tag for board in unit.boards.all())
    leases = leases_in(unit, start, now)
    return UsageReport(
        start=start,
        end=now,
        boards=boards,
        utilisation=utilisation(leases, boards, start, now),
        holder_hours=holder_hours(leases, start, now),
        idle=idle_boards(leases, boards, start, now),
        leases=len(leases),
        waiting=len(unit.requests.open()),
    )
