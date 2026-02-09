"""The database wearing the shape the job runner was written against.

The runner predates the database. It was written against the in memory board
book and lease store, which is why it is testable without either, and that is
worth keeping. These two adapters give it the same handful of methods over the
repositories instead, so the same runner works in a test with everything in
memory and on the bench with everything in postgres.

They are deliberately thin and deliberately incomplete: only the methods the
runner actually calls are here. A wider adapter would be a second inventory
with its own opinions, which is the thing this exists to avoid.
"""

from __future__ import annotations

from datetime import datetime

from fpga.inventory.boards import Board
from fpga.inventory.state import BoardState
from fpga.leases.models import Lease
from fpga.persistence.unit import UnitOfWork
from fpga.services import inventory, leasing

__all__ = [
    "BookOverRepository",
    "LeaseStoreOverRepository",
]


class BookOverRepository:
    """A board book backed by the board repository."""

    def __init__(self, unit: UnitOfWork) -> None:
        self.unit = unit

    def has(self, tag: str) -> bool:
        try:
            self.unit.boards.get(tag)
        except Exception:  # noqa: BLE001 - a missing board is the answer, not an error
            return False
        return True

    def board(self, tag: str) -> Board:
        return self.unit.boards.get(tag)

    def state(self, tag: str) -> BoardState:
        return self.unit.boards.state(tag)

    def all(self) -> tuple[Board, ...]:
        return self.unit.boards.all()

    def available(self) -> tuple[Board, ...]:
        return self.unit.boards.available()

    def in_state(self, state: str) -> tuple[Board, ...]:
        return tuple(
            board
            for board in self.unit.boards.all()
            if self.unit.boards.state(board.tag).state == state
        )

    def move_to(
        self,
        tag: str,
        to_state: str,
        when: datetime,
        *,
        reason: str = "",
        actor: str = "",
    ) -> BoardState:
        return inventory.move_state(
            self.unit,
            tag,
            to_state,
            when,
            reason=reason,
            actor=actor,
        )


class LeaseStoreOverRepository:
    """A lease store backed by the lease repository."""

    def __init__(self, unit: UnitOfWork) -> None:
        self.unit = unit

    def for_board(self, tag: str, now: datetime) -> Lease | None:
        return self.unit.leases.for_board(tag, now)

    def lease(self, lease_id: str) -> Lease:
        return self.unit.leases.get(lease_id)

    def active(self, now: datetime) -> tuple[Lease, ...]:
        return self.unit.leases.active(now)

    def release(self, lease_id: str, now: datetime, reason: str = "released") -> Lease:
        return leasing.release(self.unit, lease_id, now, reason)
