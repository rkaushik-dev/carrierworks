"""What state a board is in, and which moves are allowed from it.

The states are the ones the lab already used on a whiteboard, which is why
there are seven of them rather than three. The transitions are written as a
table because the interesting question is nearly always "why can it not go
there", and a table can answer that; a pile of conditions cannot.

Two rules are load bearing.

Retired is terminal. A board that has been stripped for parts cannot come back
because the parts are on somebody else's board now, and a bench that lets it
come back will happily schedule a job onto a bare carrier.

Quarantine is not a fault. A faulted board has failed once and may well be
fine; a quarantined one has failed enough times that the bench has stopped
believing it, and only a person with a soldering iron gets to end that. So
quarantine cannot go straight back to available, which is exactly the shortcut
somebody takes at eleven at night when they need a board.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from fpga.errors import ValidationError
from fpga.ids import slug

__all__ = [
    "STATES",
    "BoardState",
    "allowed_moves",
    "can_move",
]

STATES: tuple[str, ...] = (
    "available",
    "leased",
    "programming",
    "faulted",
    "quarantined",
    "maintenance",
    "retired",
)

# Where each state may go, and nothing else may happen.
_MOVES: dict[str, frozenset[str]] = {
    "available": frozenset({"leased", "faulted", "maintenance", "retired"}),
    "leased": frozenset({"programming", "available", "faulted", "maintenance"}),
    "programming": frozenset({"leased", "faulted"}),
    "faulted": frozenset({"available", "quarantined", "maintenance", "retired"}),
    "quarantined": frozenset({"maintenance", "retired"}),
    "maintenance": frozenset({"available", "quarantined", "retired"}),
    "retired": frozenset(),
}

# States a board cannot be moved out of without somebody saying why.
_NEEDS_REASON = frozenset({"faulted", "quarantined", "retired", "maintenance"})


def _check(state: str) -> str:
    name = slug(state)
    if name not in _MOVES:
        raise ValidationError("no such board state", field="state", given=state)
    return name


def allowed_moves(state: str) -> tuple[str, ...]:
    """Everywhere a board in this state may go, in the listed order."""
    allowed = _MOVES[_check(state)]
    return tuple(name for name in STATES if name in allowed)


def can_move(state: str, to_state: str) -> bool:
    return _check(to_state) in _MOVES[_check(state)]


@dataclass(frozen=True, slots=True)
class BoardState:
    """Where a board is now, since when, and why."""

    state: str
    since: datetime
    reason: str = ""
    actor: str = ""

    def __post_init__(self) -> None:
        object.__setattr__(self, "state", _check(self.state))
        if self.since.tzinfo is None:
            raise ValidationError("board state timestamps are in utc", field="since")
        if self.state in _NEEDS_REASON and not self.reason.strip():
            raise ValidationError(
                "that state needs a reason",
                field="reason",
                state=self.state,
            )

    @property
    def usable(self) -> bool:
        """Whether a job could be scheduled onto a board in this state."""
        return self.state == "available"

    @property
    def busy(self) -> bool:
        return self.state in ("leased", "programming")

    @property
    def out_of_service(self) -> bool:
        return self.state in ("faulted", "quarantined", "maintenance", "retired")

    @property
    def terminal(self) -> bool:
        return not _MOVES[self.state]

    def moves(self) -> tuple[str, ...]:
        return allowed_moves(self.state)

    def move(
        self,
        to_state: str,
        when: datetime,
        *,
        reason: str = "",
        actor: str = "",
    ) -> BoardState:
        """The next state, or a refusal naming what was allowed instead."""
        target = _check(to_state)
        if not can_move(self.state, target):
            raise ValidationError(
                "a board cannot go from " + self.state + " to " + target,
                field="state",
                allowed=", ".join(self.moves()) or "nowhere",
            )
        if when < self.since:
            raise ValidationError(
                "a board cannot change state before it entered the last one",
                field="when",
            )
        return BoardState(state=target, since=when, reason=reason, actor=actor)

    def held_seconds(self, now: datetime) -> int:
        if now < self.since:
            raise ValidationError("that is before the board entered this state", field="now")
        return int((now - self.since).total_seconds())

    def describe(self) -> str:
        line = f"{self.state} since {self.since.isoformat(timespec='seconds')}"
        if self.reason:
            line = f"{line}: {self.reason}"
        if self.actor:
            line = f"{line} ({self.actor})"
        return line
