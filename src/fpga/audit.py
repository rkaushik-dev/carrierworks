"""One line of the trail, and nothing that knows where it is stored.

The trail is deliberately thin prose rather than a structured event, because
the question it answers is always read by a person and never by a program.
Who took the board, when, and what they said it was for.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from fpga.errors import ValidationError
from fpga.ids import slug

__all__ = ["AuditEntry"]


@dataclass(frozen=True, slots=True)
class AuditEntry:
    """One thing somebody did."""

    at: datetime
    actor: str
    action: str
    subject: str = ""
    detail: str = ""
    key_id: str = ""

    def __post_init__(self) -> None:
        object.__setattr__(self, "actor", slug(self.actor))
        object.__setattr__(self, "action", slug(self.action))
        if self.at.tzinfo is None:
            raise ValidationError("audit times are in utc", field="at")

    def describe(self) -> str:
        line = f"{self.at.isoformat(timespec='seconds')} {self.actor} {self.action}"
        if self.subject:
            line = f"{line} {self.subject}"
        return f"{line}: {self.detail}" if self.detail else line
