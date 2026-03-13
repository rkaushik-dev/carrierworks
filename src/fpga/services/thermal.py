"""Temperature readings, and the supervisor pass that runs off them.

The supervisor was written against a log in memory, and the reason it stays
that way is that a tick has to look at a window of readings for every board at
once. Loading that window in one query and handing it over as a log costs one
round trip; asking the database per board inside the decision loop costs one
per board per tick, on the busiest path the bench has.

So this module does three things: it writes readings, it loads a window, and
it runs a tick over that window. Everything it decides comes back as actions
rather than as changes it has already made, except the shutdown path, which
has to act while it still has the transaction.
"""

from __future__ import annotations

from datetime import datetime, timedelta

from fpga.audit import AuditEntry
from fpga.config import BenchSettings
from fpga.persistence.unit import UnitOfWork
from fpga.services.adapters import BookOverRepository, LeaseStoreOverRepository
from fpga.temperature import ThermalLimits
from fpga.thermal.alarms import AlarmBook, ThermalStatus
from fpga.thermal.samples import ThermalLog, ThermalSample
from fpga.thermal.supervisor import SupervisorAction, ThermalSupervisor

__all__ = [
    "limits_for",
    "load_window",
    "prune",
    "record",
    "status_of",
    "tick",
]


def record(
    unit: UnitOfWork,
    board: str,
    at: datetime,
    millidegrees: int,
    *,
    source: str = "sysmon",
) -> ThermalSample:
    """Store one reading, refusing one for a board the bench does not have."""
    unit.boards.get(board)
    return unit.thermal.record(
        ThermalSample(board=board, at=at, millidegrees=millidegrees, source=source)
    )


def load_window(unit: UnitOfWork, now: datetime, length: timedelta) -> ThermalLog:
    """Every reading in the last stretch, as a log."""
    return unit.thermal.load(now - length)


def limits_for(unit: UnitOfWork) -> ThermalLimits:
    """The tightest limits any part on the bench is rated for.

    One book watches the whole bench, so it gets one set of thresholds, and
    the only safe way to pick them is the coolest part fitted. A rack of
    industrial parts with one commercial board in it is judged by the
    commercial board, which is the board that will fail first.
    """
    boards = unit.boards.all()
    if not boards:
        return ThermalLimits.from_junction_range((0, 85))
    tops = [board.part.junction_range[1] for board in boards]
    return ThermalLimits.from_junction_range((0, min(tops)))


def status_of(
    unit: UnitOfWork,
    now: datetime,
    *,
    settings: BenchSettings | None = None,
    window: timedelta = timedelta(minutes=15),
) -> tuple[ThermalStatus, ...]:
    """Where every board stands, worst first, without acting on any of it.

    Every board on the bench gets a line, including the ones that have not
    reported at all. A board missing from the answer looks like a board that
    is fine, and the silent ones are the ones worth looking at.
    """
    settings = settings or BenchSettings()
    log = load_window(unit, now, window)
    alarms = AlarmBook(
        limits_for(unit),
        stale_after=timedelta(seconds=settings.thermal_stale_seconds),
    )
    for board in unit.boards.all():
        alarms.update(board.tag, log, now)
    return alarms.worst_first()


def tick(
    unit: UnitOfWork,
    now: datetime,
    *,
    settings: BenchSettings | None = None,
    window: timedelta = timedelta(minutes=15),
    alarms: AlarmBook | None = None,
) -> tuple[SupervisorAction, ...]:
    """One supervisor pass over the stored readings.

    An alarm book can be passed in, and a long running poller should pass the
    same one every tick: the states have hysteresis in them, and a book built
    fresh each time has no history to be hysteretic about.
    """
    settings = settings or BenchSettings()
    book = AlarmBook(
        limits_for(unit),
        stale_after=timedelta(seconds=settings.thermal_stale_seconds),
    )
    supervisor = ThermalSupervisor(
        BookOverRepository(unit),
        alarms if alarms is not None else book,
        LeaseStoreOverRepository(unit),
    )
    actions = supervisor.tick(load_window(unit, now, window), now)
    for action in actions:
        if action.action in ("shutdown", "warn"):
            unit.audit.add(
                AuditEntry(
                    at=now,
                    actor="thermal",
                    action=action.action,
                    subject=action.board,
                    detail=action.reason[:240],
                )
            )
    return actions


def prune(unit: UnitOfWork, now: datetime, keep: timedelta = timedelta(days=7)) -> int:
    """Drop readings older than the retention window."""
    return unit.thermal.prune(now - keep)
