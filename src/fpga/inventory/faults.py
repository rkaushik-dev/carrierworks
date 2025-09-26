"""Faults, and the point at which the bench stops believing a board.

Every board fails sometimes. A cable gets nudged, a rail sags while somebody is
plugging in a supply on the shelf below, a verification read comes back wrong
once and right the next four times. Treating each of those as a reason to pull
a board would empty the rack in a week.

So faults are recorded rather than acted on, and a policy decides when the
pattern is worth something. The policy is deliberately conservative on the
kinds of fault that come and go, and immediate on the kinds that do not: a
verify failure means the flash wrote something other than what was sent, and
two of those on one board is a part that is wearing out rather than a bad day.

The window matters as much as the count. Three faults in a fortnight is a board
being used; three faults in an afternoon is a board that has broken.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from fpga.errors import ValidationError
from fpga.ids import slug
from fpga.records import board_key, in_window

__all__ = [
    "FAULT_KINDS",
    "FaultLog",
    "FaultRecord",
    "QuarantinePolicy",
]

FAULT_KINDS: tuple[str, ...] = ("chain", "program", "verify", "thermal", "power", "other")


@dataclass(frozen=True, slots=True)
class FaultRecord:
    """One thing that went wrong on one board."""

    board: str
    kind: str
    at: datetime
    detail: str = ""
    job: str = ""

    def __post_init__(self) -> None:
        object.__setattr__(self, "board", board_key(self.board))
        object.__setattr__(self, "kind", slug(self.kind))
        if self.kind not in FAULT_KINDS:
            raise ValidationError("no such fault kind", field="kind", given=self.kind)
        if self.at.tzinfo is None:
            raise ValidationError("fault times are in utc", field="at")

    def describe(self) -> str:
        line = f"{self.board} {self.kind} at {self.at.isoformat(timespec='seconds')}"
        if self.detail:
            line = f"{line}: {self.detail}"
        if self.job:
            line = f"{line} [{self.job}]"
        return line


class FaultLog:
    """Every fault the bench has seen, in the order it saw them."""

    def __init__(self, records: list[FaultRecord] | None = None) -> None:
        self._records: list[FaultRecord] = list(records or [])

    def record(self, fault: FaultRecord) -> FaultRecord:
        self._records.append(fault)
        return fault

    def add(
        self,
        board: str,
        kind: str,
        at: datetime,
        *,
        detail: str = "",
        job: str = "",
    ) -> FaultRecord:
        return self.record(FaultRecord(board=board, kind=kind, at=at, detail=detail, job=job))

    def all(self) -> tuple[FaultRecord, ...]:
        return tuple(self._records)

    def for_board(self, board: str) -> tuple[FaultRecord, ...]:
        tag = board_key(board)
        return tuple(record for record in self._records if record.board == tag)

    def within(
        self,
        board: str,
        now: datetime,
        window: timedelta,
        *,
        kind: str | None = None,
    ) -> tuple[FaultRecord, ...]:
        """Faults on a board inside a window ending now."""
        wanted = None if kind is None else slug(kind)
        inside = in_window(self.for_board(board), now, window, at=lambda record: record.at)
        return tuple(record for record in inside if wanted is None or record.kind == wanted)

    def count(
        self,
        board: str,
        now: datetime,
        window: timedelta,
        *,
        kind: str | None = None,
    ) -> int:
        return len(self.within(board, now, window, kind=kind))

    def last(self, board: str) -> FaultRecord | None:
        found = self.for_board(board)
        return found[-1] if found else None

    def boards(self) -> tuple[str, ...]:
        return tuple(sorted({record.board for record in self._records}))

    def clear(self, board: str) -> int:
        """Forget a board's faults, which is what maintenance does.

        Returns how many were dropped, because that number goes in the
        maintenance record and somebody will want it later.
        """
        tag = board_key(board)
        before = len(self._records)
        self._records = [record for record in self._records if record.board != tag]
        return before - len(self._records)


@dataclass(frozen=True, slots=True)
class QuarantinePolicy:
    """When a pattern of faults is enough to pull a board."""

    window: timedelta = timedelta(days=7)
    total_faults: int = 3
    short_window: timedelta = timedelta(hours=8)
    short_window_faults: int = 2
    verify_faults: int = 2

    def __post_init__(self) -> None:
        if self.total_faults < 1 or self.short_window_faults < 1 or self.verify_faults < 1:
            raise ValidationError("a threshold is at least one fault", field="policy")
        if self.short_window > self.window:
            raise ValidationError(
                "the short window is inside the long one",
                field="short_window",
            )

    def verdict(self, log: FaultLog, board: str, now: datetime) -> str | None:
        """Why this board should be quarantined, or nothing if it should not.

        Checked most specific first, so the reason an operator reads is the
        sharpest one that applies rather than whichever fired alphabetically.
        """
        verifies = log.count(board, now, self.window, kind="verify")
        if verifies >= self.verify_faults:
            return f"{verifies} verify failures inside {_days(self.window)}"
        recent = log.count(board, now, self.short_window)
        if recent >= self.short_window_faults:
            return f"{recent} faults inside {_hours(self.short_window)}"
        total = log.count(board, now, self.window)
        if total >= self.total_faults:
            return f"{total} faults inside {_days(self.window)}"
        return None

    def should_quarantine(self, log: FaultLog, board: str, now: datetime) -> bool:
        return self.verdict(log, board, now) is not None


def _days(window: timedelta) -> str:
    days = window.days
    return "a day" if days == 1 else f"{days} days"


def _hours(window: timedelta) -> str:
    hours = int(window.total_seconds() // 3600)
    return "an hour" if hours == 1 else f"{hours} hours"
