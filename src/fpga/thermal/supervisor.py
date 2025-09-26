"""The only thing in the bench that acts on temperature.

Everything below this decides; this does. It runs on a tick, folds the samples
into alarm states, and turns the states that matter into actions: a board over
its shutdown threshold loses its power and its lease, a board that has gone
quiet while somebody is using it is treated as unsafe rather than as fine, and
a board that is merely warm produces a warning naming the boards downwind of
it, because those are the ones that will be next.

Two decisions here are worth defending.

Shutdown does not ask the lease holder. There is no priority high enough to
justify cooking a part, and a job that loses its board to a thermal shutdown
gets a clear reason rather than a silent failure twenty minutes later.

A stale board that nobody holds is left alone. It is almost certainly a board
that has been powered off, and taking action on every idle board would fill the
log with noise that hides the one case that matters.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from fpga.inventory.book import BoardBook
from fpga.leases.store import LeaseStore
from fpga.locations import downwind_of
from fpga.power.pdu import PowerController
from fpga.records import describe_tally, tally
from fpga.thermal.alarms import UNKNOWN, AlarmBook, ThermalStatus
from fpga.thermal.samples import ThermalLog

__all__ = [
    "SupervisorAction",
    "ThermalSupervisor",
]


@dataclass(frozen=True, slots=True)
class SupervisorAction:
    """Something the supervisor did, or would have done."""

    board: str
    action: str
    reason: str
    affected: tuple[str, ...] = ()

    def describe(self) -> str:
        line = f"{self.board} {self.action}: {self.reason}"
        if self.affected:
            return f"{line} (downwind: {', '.join(self.affected)})"
        return line


class ThermalSupervisor:
    """Reads the samples, decides, and acts on the ones that matter."""

    def __init__(
        self,
        book: BoardBook,
        alarms: AlarmBook,
        leases: LeaseStore,
        power: PowerController | None = None,
        *,
        unknown_grace: timedelta = timedelta(minutes=5),
        downwind_reach: int = 2,
    ) -> None:
        self.book = book
        self.alarms = alarms
        self.leases = leases
        self.power = power
        self.unknown_grace = unknown_grace
        self.downwind_reach = downwind_reach

    def tick(self, log: ThermalLog, now: datetime) -> tuple[SupervisorAction, ...]:
        """One pass: update every board's state and act on what it says."""
        actions: list[SupervisorAction] = []
        for board in self.book.all():
            status = self.alarms.update(board.tag, log, now)
            action = self._act(status, now)
            if action is not None:
                actions.append(action)
        return tuple(actions)

    def _act(self, status: ThermalStatus, now: datetime) -> SupervisorAction | None:
        board = status.board
        held = self.leases.for_board(board, now)
        if status.state == "shutdown":
            return self._shut_down(board, now, "die over the shutdown threshold")
        if status.state == UNKNOWN:
            if held is None:
                return None
            if status.held_for(now) < self.unknown_grace:
                return SupervisorAction(
                    board=board,
                    action="watch",
                    reason="no readings while the board is in use",
                )
            return self._shut_down(board, now, "stopped reporting while in use")
        if status.state == "critical":
            return SupervisorAction(
                board=board,
                action="warn",
                reason=status.describe(),
                affected=self._downwind(board),
            )
        if status.state == "warn":
            return SupervisorAction(board=board, action="note", reason=status.describe())
        return None

    def _shut_down(self, board: str, now: datetime, reason: str) -> SupervisorAction:
        """Power the board off, give the lease back, and take it out of service."""
        held = self.leases.for_board(board, now)
        if held is not None:
            self.leases.release(held.id, now, "thermal shutdown")
        if self.power is not None and self.book.has(board):
            self.power.power_off(self.book.board(board))
        if self.book.state(board).state != "faulted":
            self.book.move_to(board, "faulted", now, reason=reason, actor="thermal")
        return SupervisorAction(
            board=board,
            action="shutdown",
            reason=reason,
            affected=self._downwind(board),
        )

    def _downwind(self, board: str) -> tuple[str, ...]:
        """Which boards sit in this one's warm air."""
        if not self.book.has(board):  # pragma: no cover - the caller reads the book
            return ()
        source = self.book.board(board).location
        others = {other.tag: other.location for other in self.book.all() if other.tag != board}
        return downwind_of(source, others, reach=self.downwind_reach)

    def worst(self) -> ThermalStatus | None:
        statuses = self.alarms.worst_first()
        return statuses[0] if statuses else None

    def summary(self) -> str:
        counts = tally(status.state for status in self.alarms.worst_first())
        return describe_tally(counts, empty="nothing being watched")
