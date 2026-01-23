"""Adding boards, moving their state, and changing what is fitted to them.

Every one of these goes through the same three steps: read the board, ask the
domain object whether what is being asked for is allowed, and write the answer
back. The domain refuses the impossible ones, so the service never has a rule
of its own to enforce and never has to duplicate one.

Faulting a board is the exception worth reading. It has to release whatever
holds the board as well as moving it, because a faulted board with a live
lease on it is a board nobody can take and the holder cannot use.
"""

from __future__ import annotations

from datetime import datetime

from fpga.errors import Conflict
from fpga.inventory.boards import Board
from fpga.inventory.state import BoardState
from fpga.leases.models import Lease
from fpga.persistence.unit import UnitOfWork
from fpga.records import board_key
from fpga.services import audit

__all__ = [
    "add_board",
    "fault_board",
    "fit_card",
    "list_boards",
    "move_state",
]


def check_free(unit: UnitOfWork, board: Board) -> None:
    """Refuse a board that would collide with one already on the bench.

    The database enforces all three of these as well, and it is the one that
    matters when two writers race. This exists so that the ordinary case gets
    a message naming the board in the way rather than a constraint name.
    """
    for other in unit.boards.all():
        if other.tag == board.tag:
            continue
        if other.location == board.location:
            raise Conflict(
                "another board is already in that slot",
                board=board.tag,
                holder=other.tag,
                location=str(board.location),
            )
        if (
            board.adapter is not None
            and other.adapter is not None
            and other.adapter.serial == board.adapter.serial
        ):
            raise Conflict(
                "that cable is already on another board",
                board=board.tag,
                holder=other.tag,
                serial=board.adapter.serial,
            )
        if board.pdu is not None and other.pdu == board.pdu:
            raise Conflict(
                "that outlet already feeds another board",
                board=board.tag,
                holder=other.tag,
                outlet=str(board.pdu),
            )


def add_board(unit: UnitOfWork, board: Board, now: datetime, *, state: str = "available") -> Board:
    """Put a board on the bench, in whatever state it arrived in."""
    check_free(unit, board)
    reason = "" if state in ("available", "leased", "programming") else "added to the bench"
    unit.boards.add(board, BoardState(state=state, since=now, reason=reason))
    return board


def list_boards(unit: UnitOfWork, *, only_available: bool = False) -> tuple[Board, ...]:
    return unit.boards.available() if only_available else unit.boards.all()


def move_state(
    unit: UnitOfWork,
    tag: str,
    to_state: str,
    now: datetime,
    *,
    reason: str = "",
    actor: str = "",
) -> BoardState:
    """Move a board's state through the state machine's own rules."""
    current = unit.boards.state(tag)
    moved = current.move(to_state, now, reason=reason, actor=actor)
    unit.boards.save_state(tag, moved)
    audit.record(
        unit,
        now,
        "board-state",
        actor=actor or "bench",
        subject=board_key(tag),
        detail=f"{current.state} to {moved.state} {reason}".strip(),
    )
    return moved


def fault_board(
    unit: UnitOfWork,
    tag: str,
    now: datetime,
    reason: str,
    *,
    actor: str = "bench",
) -> tuple[BoardState, Lease | None]:
    """Take a board out of service and give back whatever held it."""
    held = unit.leases.for_board(tag, now)
    released = None
    if held is not None:
        released = held.release(now, "board faulted")
        unit.leases.save(released)
    current = unit.boards.state(tag)
    if current.state == "faulted":
        return (current, released)
    moved = current.move("faulted", now, reason=reason, actor=actor)
    unit.boards.save_state(tag, moved)
    audit.record(
        unit,
        now,
        "board-faulted",
        actor=actor,
        subject=board_key(tag),
        detail=reason,
    )
    return (moved, released)


def fit_card(unit: UnitOfWork, tag: str, slot: str, card: str | None) -> Board:
    """Record that somebody has fitted or removed a card.

    The board rebuilds itself and refuses the combinations that cannot work,
    so a card in the wrong slot is caught here rather than at the next build.
    """
    board = unit.boards.get(tag)
    return unit.boards.replace(board.with_card(slot, card))
