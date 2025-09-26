"""What has been done to a board, and what is due.

Two kinds of thing live in the same book because they are the same act from the
bench's point of view: somebody took the board out of service, did something to
it, and put it back. A repair and a calibration differ in why they happened and
in what they mean afterwards.

Calibration is where the interesting rule is. A bare carrier does not need
calibrating, because there is nothing on it whose readings anybody trusts. A
carrier with a converter card on it does, annually, because the results of a
capture run are only meaningful if somebody has checked the front end against a
reference in the last year. So the interval is not a property of the board, it
is a property of what is fitted to it today, and fitting a card to a board that
has never been calibrated makes it immediately due.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from fpga.errors import ValidationError
from fpga.ids import slug
from fpga.inventory.boards import Board
from fpga.records import board_key

__all__ = [
    "CALIBRATION_INTERVAL",
    "MAINTENANCE_KINDS",
    "MaintenanceBook",
    "MaintenanceRecord",
    "calibration_interval",
]

MAINTENANCE_KINDS: tuple[str, ...] = ("repair", "calibration", "inspection", "upgrade")

# Anything measuring an analogue signal is calibrated once a year.
CALIBRATION_INTERVAL = timedelta(days=365)

_MEASURING = ("adc-", "dac-")


@dataclass(frozen=True, slots=True)
class MaintenanceRecord:
    """One visit to the bench with a screwdriver or a reference source."""

    board: str
    kind: str
    at: datetime
    by: str
    notes: str = ""
    faults_cleared: int = 0

    def __post_init__(self) -> None:
        object.__setattr__(self, "board", board_key(self.board))
        object.__setattr__(self, "kind", slug(self.kind))
        if self.kind not in MAINTENANCE_KINDS:
            raise ValidationError("no such maintenance kind", field="kind", given=self.kind)
        if self.at.tzinfo is None:
            raise ValidationError("maintenance times are in utc", field="at")
        if not self.by.strip():
            raise ValidationError("somebody did the work", field="by")
        if self.faults_cleared < 0:
            raise ValidationError("cannot clear a negative number of faults", field="faults")

    def describe(self) -> str:
        line = f"{self.board} {self.kind} on {self.at.date().isoformat()} by {self.by}"
        return f"{line}: {self.notes}" if self.notes else line


def calibration_interval(board: Board) -> timedelta | None:
    """How often this board needs calibrating, or nothing if it does not.

    Read off the cards fitted right now rather than off the board, so removing
    the converter takes the requirement away with it.
    """
    for spec in board.fitted_cards():
        if any(capability.startswith(_MEASURING) for capability in spec.provides):
            return CALIBRATION_INTERVAL
    return None


class MaintenanceBook:
    """Every record, and the questions the lab asks of them."""

    def __init__(self, records: list[MaintenanceRecord] | None = None) -> None:
        self._records: list[MaintenanceRecord] = list(records or [])

    def record(self, entry: MaintenanceRecord) -> MaintenanceRecord:
        self._records.append(entry)
        return entry

    def add(
        self,
        board: str,
        kind: str,
        at: datetime,
        by: str,
        *,
        notes: str = "",
        faults_cleared: int = 0,
    ) -> MaintenanceRecord:
        return self.record(
            MaintenanceRecord(
                board=board,
                kind=kind,
                at=at,
                by=by,
                notes=notes,
                faults_cleared=faults_cleared,
            )
        )

    def all(self) -> tuple[MaintenanceRecord, ...]:
        return tuple(self._records)

    def for_board(self, board: str) -> tuple[MaintenanceRecord, ...]:
        tag = board_key(board)
        return tuple(record for record in self._records if record.board == tag)

    def last(self, board: str, kind: str | None = None) -> MaintenanceRecord | None:
        wanted = None if kind is None else slug(kind)
        found = [
            record for record in self.for_board(board) if wanted is None or record.kind == wanted
        ]
        return max(found, key=lambda record: record.at) if found else None

    def calibrated_at(self, board: str) -> datetime | None:
        last = self.last(board, "calibration")
        return None if last is None else last.at

    def calibration_due(self, board: Board, now: datetime) -> datetime | None:
        """When this board's calibration runs out, or nothing if it needs none.

        A board that needs calibration and has never had any is due now, which
        is the honest answer rather than a special case.
        """
        interval = calibration_interval(board)
        if interval is None:
            return None
        last = self.calibrated_at(board.tag)
        return now if last is None else last + interval

    def calibration_valid(self, board: Board, now: datetime) -> bool:
        due = self.calibration_due(board, now)
        return due is None or due > now

    def overdue(self, boards: tuple[Board, ...], now: datetime) -> tuple[Board, ...]:
        """Every board whose calibration has run out, soonest expired first."""
        late = [board for board in boards if not self.calibration_valid(board, now)]
        return tuple(sorted(late, key=lambda board: self.calibrated_at(board.tag) or now))

    def faults_cleared_for(self, board: str) -> int:
        return sum(record.faults_cleared for record in self.for_board(board))

    def boards(self) -> tuple[str, ...]:
        return tuple(sorted({record.board for record in self._records}))
