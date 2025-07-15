"""Every board on the bench, with the uniqueness rules the room enforces.

Four things cannot be shared and the bench has been wrong about all four at
some point. Two boards cannot have one asset tag, because the tag is how a
person and the database agree on which board they mean. Two boards cannot be in
one slot, because there is only one slot. One cable cannot be on two boards,
which is the mistake that happens when a board is moved and the old row is not
updated, and the symptom is a job programming the wrong board. And one outlet
cannot feed two boards, because power cycling one would take the other down in
the middle of whatever it was doing.

The book holds state beside each board rather than inside it. A board is a
physical fact that changes when somebody picks up a screwdriver; its state
changes many times an hour. Keeping them apart means a state change does not
rewrite the row that says what is fitted.
"""

from __future__ import annotations

from datetime import datetime

from fpga.capabilities import RequirementSet
from fpga.errors import ConfigurationError, NotFound
from fpga.ids import AssetTag
from fpga.inventory.boards import Board, BoardSummary
from fpga.inventory.profile import matches
from fpga.inventory.state import BoardState

__all__ = ["BoardBook"]


class BoardBook:
    """The inventory, in memory, with its own consistency rules."""

    def __init__(self, when: datetime | None = None) -> None:
        self._boards: dict[str, Board] = {}
        self._states: dict[str, BoardState] = {}
        self._epoch = when

    def add(
        self,
        board: Board,
        *,
        when: datetime | None = None,
        state: str = "available",
        reason: str = "",
    ) -> Board:
        """Put a board in the book, refusing anything that collides."""
        tag = board.tag
        if tag in self._boards:
            raise ConfigurationError("that asset tag is already on the bench", board=tag)
        self._check_free(board)
        moment = when or self._epoch
        if moment is None:
            raise ConfigurationError("a board arrives at a time", board=tag)
        self._boards[tag] = board
        settled = reason.strip()
        if not settled and state not in ("available", "leased", "programming"):
            settled = "added to the bench"
        self._states[tag] = BoardState(state=state, since=moment, reason=settled)
        return board

    def _check_free(self, board: Board, *, ignoring: str = "") -> None:
        for other in self._boards.values():
            if other.tag == ignoring:
                continue
            if other.location == board.location:
                raise ConfigurationError(
                    "another board is already in that slot",
                    board=board.tag,
                    holder=other.tag,
                    location=str(board.location),
                )
            cable_shared = (
                board.adapter is not None
                and other.adapter is not None
                and other.adapter.serial == board.adapter.serial
            )
            if cable_shared and board.adapter is not None:
                raise ConfigurationError(
                    "that cable is already on another board",
                    board=board.tag,
                    holder=other.tag,
                    serial=board.adapter.serial,
                )
            if board.pdu is not None and other.pdu == board.pdu:
                raise ConfigurationError(
                    "that outlet already feeds another board",
                    board=board.tag,
                    holder=other.tag,
                    outlet=str(board.pdu),
                )

    def replace(self, board: Board) -> Board:
        """Write a changed board back, keeping its state."""
        tag = board.tag
        if tag not in self._boards:
            raise NotFound("board", tag)
        self._check_free(board, ignoring=tag)
        self._boards[tag] = board
        return board

    def remove(self, tag: str) -> Board:
        key = str(AssetTag.parse(tag))
        if key not in self._boards:
            raise NotFound("board", key)
        self._states.pop(key, None)
        return self._boards.pop(key)

    def board(self, tag: str) -> Board:
        key = str(AssetTag.parse(tag))
        found = self._boards.get(key)
        if found is None:
            raise NotFound("board", key)
        return found

    def has(self, tag: str) -> bool:
        return str(AssetTag.parse(tag)) in self._boards

    def state(self, tag: str) -> BoardState:
        key = self.board(tag).tag
        return self._states[key]

    def move_to(
        self,
        tag: str,
        to_state: str,
        when: datetime,
        *,
        reason: str = "",
        actor: str = "",
    ) -> BoardState:
        """Move one board's state, through the state machine's own rules."""
        key = self.board(tag).tag
        moved = self._states[key].move(to_state, when, reason=reason, actor=actor)
        self._states[key] = moved
        return moved

    def all(self) -> tuple[Board, ...]:
        return tuple(self._boards[tag] for tag in sorted(self._boards))

    def tags(self) -> tuple[str, ...]:
        return tuple(sorted(self._boards))

    def __len__(self) -> int:
        return len(self._boards)

    def in_state(self, state: str) -> tuple[Board, ...]:
        return tuple(board for board in self.all() if self._states[board.tag].state == state)

    def available(self) -> tuple[Board, ...]:
        return tuple(board for board in self.all() if self._states[board.tag].usable)

    def in_rack(self, rack: str) -> tuple[Board, ...]:
        return tuple(board for board in self.all() if board.location.rack == rack.strip().lower())

    def with_device(self, device: str) -> tuple[Board, ...]:
        wanted = device.strip().lower()
        return tuple(board for board in self.all() if board.device().device == wanted)

    def matching(self, wanted: RequirementSet, *, only_available: bool = True) -> tuple[Board, ...]:
        """Every board that could take a job asking for this."""
        pool = self.available() if only_available else self.all()
        return tuple(board for board in pool if matches(board, wanted))

    def locations(self) -> dict[str, str]:
        return {board.tag: str(board.location) for board in self.all()}

    def summaries(self) -> tuple[BoardSummary, ...]:
        return tuple(BoardSummary.of(board) for board in self.all())

    def describe(self) -> str:
        counts: dict[str, int] = {}
        for board in self.all():
            state = self._states[board.tag].state
            counts[state] = counts.get(state, 0) + 1
        parts = [f"{count} {state}" for state, count in sorted(counts.items())]
        return f"{len(self)} boards: " + ", ".join(parts) if parts else "no boards"
