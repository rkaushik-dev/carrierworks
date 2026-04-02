"""Maintenance visits, calibration dates, and getting a board back in service.

Two rules live here rather than in the maintenance book, because both of them
need the rest of the bench. A board coming back from a visit goes to available
only if nothing else is wrong with it, and a board whose calibration has run
out cannot be handed to somebody who needs a measurement, whatever state it is
in.

Calibration is the part people get wrong. It expires on a date, not on use, so
a board sitting in a drawer goes out of calibration exactly as fast as one that
runs every night, and the bench has to say so before somebody trusts a capture
off it.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from fpga.errors import ValidationError
from fpga.inventory.maintenance import (
    MAINTENANCE_KINDS,
    MaintenanceRecord,
    calibration_interval,
)
from fpga.persistence.unit import UnitOfWork
from fpga.services import inventory

__all__ = [
    "CalibrationState",
    "calibration",
    "log_visit",
    "overdue",
    "return_to_service",
]


@dataclass(frozen=True, slots=True)
class CalibrationState:
    """Where one board stands on calibration."""

    board: str
    required: bool
    last_at: datetime | None
    due_at: datetime | None
    valid: bool

    @property
    def never(self) -> bool:
        return self.required and self.last_at is None

    def describe(self) -> str:
        if not self.required:
            return f"{self.board} needs no calibration"
        if self.last_at is None:
            return f"{self.board} has never been calibrated"
        when = "no expiry" if self.due_at is None else self.due_at.date().isoformat()
        return f"{self.board} calibrated {self.last_at.date().isoformat()}, due {when}"


def log_visit(
    unit: UnitOfWork,
    board: str,
    kind: str,
    at: datetime,
    by: str,
    *,
    notes: str = "",
    faults_cleared: int = 0,
) -> MaintenanceRecord:
    """Write down a visit, refusing a kind the bench does not know."""
    if kind.strip().lower() not in MAINTENANCE_KINDS:
        raise ValidationError(
            "no such maintenance kind",
            field="kind",
            given=kind,
            known=", ".join(sorted(MAINTENANCE_KINDS)),
        )
    unit.boards.get(board)
    return unit.maintenance.add(
        MaintenanceRecord(
            board=board,
            kind=kind,
            at=at,
            by=by,
            notes=notes,
            faults_cleared=faults_cleared,
        )
    )


def calibration(unit: UnitOfWork, board: str, now: datetime) -> CalibrationState:
    """When this board was last calibrated and whether that still holds."""
    found = unit.boards.get(board)
    book = unit.maintenance.book()
    return CalibrationState(
        board=found.tag,
        required=calibration_interval(found) is not None,
        last_at=book.calibrated_at(found.tag),
        due_at=book.calibration_due(found, now),
        valid=book.calibration_valid(found, now),
    )


def overdue(unit: UnitOfWork, now: datetime) -> tuple[CalibrationState, ...]:
    """Every board whose calibration has run out or never happened."""
    book = unit.maintenance.book()
    out: list[CalibrationState] = []
    for board in book.overdue(unit.boards.all(), now):
        out.append(calibration(unit, board.tag, now))
    return tuple(out)


def return_to_service(
    unit: UnitOfWork,
    board: str,
    at: datetime,
    by: str,
    *,
    kind: str = "repair",
    notes: str = "",
    faults_cleared: int = 0,
) -> MaintenanceRecord:
    """Log the visit and put the board back, in one transaction.

    The state move is the point. A board left in maintenance because somebody
    finished the work and forgot the second command is the most common way a
    board disappears off the bench for a week.
    """
    record = log_visit(
        unit,
        board,
        kind,
        at,
        by,
        notes=notes,
        faults_cleared=faults_cleared,
    )
    inventory.move_state(
        unit,
        board,
        "available",
        at,
        reason=notes or f"{kind} finished",
        actor=by,
    )
    return record
