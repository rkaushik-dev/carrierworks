"""Writing down who did what, and reading it back when somebody asks.

Every write the bench performs on somebody's behalf gets a line: who asked,
what they asked for, and what it landed on. The trail is deliberately thin
prose rather than a structured event, because the question it answers is
always read by a person and never by a program.

Nothing here fails the operation it is recording. An audit write that raised
would turn a successful lease into an error, and the bench would rather have a
lease with no line about it than a person unable to take a board because the
logging is broken. The line is written in the same transaction, so it lands
with the change or not at all, and that is the strongest guarantee worth
having.
"""

from __future__ import annotations

from datetime import datetime, timedelta

from fpga.access import ApiKey
from fpga.audit import AuditEntry
from fpga.errors import ValidationError
from fpga.persistence.unit import UnitOfWork

__all__ = [
    "for_subject",
    "record",
    "recent",
    "since",
]


def record(
    unit: UnitOfWork,
    now: datetime,
    action: str,
    *,
    actor: str = "bench",
    subject: str = "",
    detail: str = "",
    key: ApiKey | None = None,
) -> AuditEntry:
    """Write one line, in the same transaction as whatever it describes."""
    entry = AuditEntry(
        at=now,
        actor=actor if key is None else key.label,
        action=action,
        subject=subject,
        detail=detail[:240],
        key_id="" if key is None else key.id,
    )
    return unit.audit.add(entry)


def recent(unit: UnitOfWork, limit: int = 50) -> tuple[AuditEntry, ...]:
    """The last lines written, newest first."""
    if limit < 1:
        raise ValidationError("ask for at least one line", field="limit", given=limit)
    return unit.audit.recent(limit)


def for_subject(unit: UnitOfWork, subject: str, limit: int = 50) -> tuple[AuditEntry, ...]:
    """Everything done to one board, image or lease."""
    return unit.audit.for_subject(subject, limit)


def since(unit: UnitOfWork, now: datetime, window: timedelta) -> tuple[AuditEntry, ...]:
    """The trail over a window, which is what the daily report reads."""
    if window <= timedelta(0):
        raise ValidationError("a window has some length", field="window")
    return unit.audit.since(now - window)


def search(
    unit: UnitOfWork,
    *,
    subject: str | None = None,
    actor: str | None = None,
    action: str | None = None,
    since: datetime | None = None,
    limit: int = 50,
) -> tuple[AuditEntry, ...]:
    """The trail, narrowed by whatever the caller knows."""
    return unit.audit.search(
        subject=subject,
        actor=actor,
        action=action,
        since=since,
        limit=limit,
    )
