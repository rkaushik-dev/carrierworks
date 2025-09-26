"""The shapes four books had each grown their own copy of.

Faults, maintenance, thermal samples and wear counts are four different things
with four different rules, and underneath all of them was the same container: a
dictionary keyed by a normalised asset tag, a list per board, an accessor that
parses the tag again on the way in, and a windowed query that filters on a
timestamp. Four copies, and two of them had drifted on whether a board with no
entries should appear in the listing.

So the container is written once here, with the tag handling and the window in
it, and the books keep only what is actually theirs. Nothing in this module
knows what a fault or a sample is.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable
from datetime import datetime, timedelta
from typing import Generic, TypeVar

from fpga.errors import ValidationError
from fpga.ids import AssetTag

__all__ = [
    "PerBoard",
    "board_key",
    "describe_tally",
    "in_window",
    "tally",
]

T = TypeVar("T")


def board_key(value: str) -> str:
    """One normalised asset tag, however it was written."""
    return str(AssetTag.parse(value))


def in_window(
    items: Iterable[T],
    now: datetime,
    window: timedelta,
    *,
    at: Callable[[T], datetime],
) -> tuple[T, ...]:
    """Everything whose timestamp falls in a window ending now."""
    if window <= timedelta(0):
        raise ValidationError("a window has some length", field="window")
    start = now - window
    return tuple(item for item in items if start <= at(item) <= now)


def tally(names: Iterable[str]) -> dict[str, int]:
    """Count how many times each name appears."""
    counts: dict[str, int] = {}
    for name in names:
        counts[name] = counts.get(name, 0) + 1
    return counts


def describe_tally(counts: dict[str, int], *, empty: str = "nothing") -> str:
    """A tally as the line that goes in a status message."""
    if not counts:
        return empty
    return ", ".join(f"{count} {name}" for name, count in sorted(counts.items()))


class PerBoard(Generic[T]):
    """Entries kept per board, oldest first, optionally capped."""

    __slots__ = ("_entries", "keep")

    def __init__(self, *, keep: int | None = None) -> None:
        if keep is not None and keep < 2:
            raise ValidationError("keep at least two entries", field="keep", given=keep)
        self.keep = keep
        self._entries: dict[str, list[T]] = {}

    def append(self, board: str, item: T) -> T:
        held = self._entries.setdefault(board_key(board), [])
        held.append(item)
        if self.keep is not None and len(held) > self.keep:
            del held[: len(held) - self.keep]
        return item

    def items(self, board: str) -> tuple[T, ...]:
        return tuple(self._entries.get(board_key(board), ()))

    def latest(self, board: str) -> T | None:
        held = self._entries.get(board_key(board))
        return held[-1] if held else None

    def boards(self) -> tuple[str, ...]:
        return tuple(sorted(self._entries))

    def all(self) -> tuple[T, ...]:
        """Every entry, board by board in tag order."""
        found: list[T] = []
        for board in self.boards():
            found.extend(self._entries[board])
        return tuple(found)

    def replace(self, board: str, items: Iterable[T]) -> None:
        kept = list(items)
        if kept:
            self._entries[board_key(board)] = kept
        else:
            self._entries.pop(board_key(board), None)

    def drop(self, board: str) -> int:
        """Forget a board, returning how many entries went with it."""
        held = self._entries.pop(board_key(board), [])
        return len(held)

    def prune(self, keep: Callable[[T], bool]) -> int:
        """Drop everything the predicate does not want, and say how many."""
        dropped = 0
        for board, held in list(self._entries.items()):
            wanted = [item for item in held if keep(item)]
            dropped += len(held) - len(wanted)
            if wanted:
                self._entries[board] = wanted
            else:
                del self._entries[board]
        return dropped

    def __contains__(self, board: object) -> bool:
        return isinstance(board, str) and board_key(board) in self._entries

    def __len__(self) -> int:
        return sum(len(held) for held in self._entries.values())
