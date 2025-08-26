"""What the bench was doing, measured over a window.

Three questions get asked and they are asked by different people. The lab
manager wants utilisation, because a bench at thirty percent does not need the
two boards somebody is asking to buy. The people waiting want to know how long
the queue has been, because a median wait of four minutes and a median wait of
forty are different labs. And when the argument starts about who is hogging
what, board hours by holder settles it in one line.

Everything is computed over a window with both ends, and a lease that started
before the window or runs past the end of it is clipped rather than dropped.
Dropping them is how a busy weekend reads as an idle one.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from fpga.errors import ValidationError
from fpga.leases.models import Lease
from fpga.scheduling.queue import RequestQueue

__all__ = [
    "WaitStats",
    "busy_seconds",
    "holder_hours",
    "utilisation",
    "wait_stats",
]


def _window(start: datetime, end: datetime) -> float:
    if end <= start:
        raise ValidationError("a window ends after it starts", field="window")
    return (end - start).total_seconds()


def _overlap(lease: Lease, start: datetime, end: datetime) -> float:
    """Seconds this lease held its board inside the window."""
    began = max(lease.taken_at, start)
    finished = min(lease.released_at or lease.expires_at, end)
    return max((finished - began).total_seconds(), 0.0)


def busy_seconds(
    leases: tuple[Lease, ...],
    start: datetime,
    end: datetime,
) -> dict[str, float]:
    """How long each board was held inside the window."""
    _window(start, end)
    busy: dict[str, float] = {}
    for lease in leases:
        held = _overlap(lease, start, end)
        if held:
            busy[lease.board] = busy.get(lease.board, 0.0) + held
    return busy


def utilisation(
    leases: tuple[Lease, ...],
    boards: tuple[str, ...],
    start: datetime,
    end: datetime,
) -> dict[str, float]:
    """The fraction of the window each board spent leased, board by board.

    Boards that were never leased are included at zero, because a report that
    silently leaves out the idle boards answers the opposite of the question
    it was asked.
    """
    span = _window(start, end)
    busy = busy_seconds(leases, start, end)
    return {board: min(busy.get(board, 0.0) / span, 1.0) for board in sorted(set(boards))}


def holder_hours(
    leases: tuple[Lease, ...],
    start: datetime,
    end: datetime,
) -> dict[str, float]:
    """Board hours by holder, rounded to two places for reading."""
    _window(start, end)
    hours: dict[str, float] = {}
    for lease in leases:
        held = _overlap(lease, start, end)
        if held:
            hours[lease.holder] = hours.get(lease.holder, 0.0) + held / 3600
    return {holder: round(value, 2) for holder, value in sorted(hours.items())}


@dataclass(frozen=True, slots=True)
class WaitStats:
    """How long the queue has been for the requests still on it."""

    waiting: int
    longest: timedelta
    median: timedelta

    @property
    def empty(self) -> bool:
        return self.waiting == 0

    def describe(self) -> str:
        if self.empty:
            return "nothing waiting"
        return (
            f"{self.waiting} waiting, longest {int(self.longest.total_seconds() // 60)}m, "
            f"median {int(self.median.total_seconds() // 60)}m"
        )


def wait_stats(queue: RequestQueue, now: datetime) -> WaitStats:
    """The queue as three numbers."""
    waits = sorted(request.waited(now) for request in queue.pending())
    if not waits:
        return WaitStats(waiting=0, longest=timedelta(0), median=timedelta(0))
    middle = len(waits) // 2
    odd = len(waits) % 2
    median = waits[middle] if odd else (waits[middle - 1] + waits[middle]) / 2
    return WaitStats(waiting=len(waits), longest=waits[-1], median=median)


def idle_boards(
    leases: tuple[Lease, ...],
    boards: tuple[str, ...],
    start: datetime,
    end: datetime,
) -> tuple[str, ...]:
    """Boards nobody held at all in the window, which is the buy or sell list."""
    busy = busy_seconds(leases, start, end)
    return tuple(board for board in sorted(set(boards)) if board not in busy)
