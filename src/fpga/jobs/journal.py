"""What happened, written down as it happens, one line at a time.

The bench crashes. A worker gets killed, a machine reboots, somebody closes the
laptop the poller was running on. When it comes back, the question that matters
is which board was in the middle of being programmed, because that board is in
an unknown state and the worst thing to do with it is to hand it to somebody
else.

So every step is appended to a file before it is attempted, not after. A line
saying a flash erase started with no line saying it finished is exactly the
signal the recovery needs, and it can only exist if the write comes first.

One json object per line, flushed on every write. It is not fast and it does
not need to be: the whole bench writes a few hundred lines an hour, and being
able to read the file with an editor while a job is running has been worth more
than any amount of speed.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

from fpga.errors import ValidationError
from fpga.ids import slug

__all__ = [
    "EVENTS",
    "JobJournal",
    "JournalEntry",
]

EVENTS: tuple[str, ...] = (
    "created",
    "started",
    "step",
    "passed",
    "failed",
    "cancelled",
    "abandoned",
)

_OPEN = frozenset({"started", "step"})
_CLOSED = frozenset({"passed", "failed", "cancelled", "abandoned"})


@dataclass(frozen=True, slots=True)
class JournalEntry:
    """One line of the journal."""

    at: datetime
    job: str
    event: str
    detail: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        object.__setattr__(self, "job", slug(self.job))
        object.__setattr__(self, "event", slug(self.event))
        if self.event not in EVENTS:
            raise ValidationError("no such journal event", field="event", given=self.event)
        if self.at.tzinfo is None:
            raise ValidationError("journal times are in utc", field="at")

    @property
    def closes(self) -> bool:
        return self.event in _CLOSED

    def to_line(self) -> str:
        payload = {
            "at": self.at.isoformat(timespec="milliseconds"),
            "job": self.job,
            "event": self.event,
            **({"detail": self.detail} if self.detail else {}),
        }
        return json.dumps(payload, sort_keys=True)

    @classmethod
    def from_line(cls, line: str) -> JournalEntry:
        try:
            raw = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValidationError("that is not a journal line", field="line") from exc
        if not isinstance(raw, dict) or "at" not in raw or "job" not in raw:
            raise ValidationError("a journal line has a time and a job", field="line")
        return cls(
            at=datetime.fromisoformat(raw["at"]),
            job=raw["job"],
            event=raw.get("event", "step"),
            detail=raw.get("detail", {}),
        )

    def describe(self) -> str:
        line = f"{self.at.isoformat(timespec='seconds')} {self.job} {self.event}"
        if self.detail:
            rendered = " ".join(f"{key}={value}" for key, value in sorted(self.detail.items()))
            return f"{line} {rendered}"
        return line


class JobJournal:
    """An append only record of everything the runner did."""

    def __init__(self, path: Path | str) -> None:
        self.path = Path(path)

    def record(self, entry: JournalEntry) -> JournalEntry:
        """Write one line and flush it, before whatever it describes happens."""
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self.path.open("a", encoding="utf-8") as handle:
            handle.write(entry.to_line() + "\n")
            handle.flush()
        return entry

    def append(
        self,
        job: str,
        event: str,
        at: datetime,
        **detail: Any,
    ) -> JournalEntry:
        return self.record(JournalEntry(at=at, job=job, event=event, detail=detail))

    def entries(self) -> tuple[JournalEntry, ...]:
        if not self.path.is_file():
            return ()
        found = []
        for line in self.path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                found.append(JournalEntry.from_line(line))
        return tuple(found)

    def for_job(self, job: str) -> tuple[JournalEntry, ...]:
        wanted = slug(job)
        return tuple(entry for entry in self.entries() if entry.job == wanted)

    def last_event(self, job: str) -> JournalEntry | None:
        found = self.for_job(job)
        return found[-1] if found else None

    def unfinished(self) -> tuple[str, ...]:
        """Jobs that started and never wrote a line saying how they ended.

        These are the ones whose boards are in an unknown state after a
        crash, and they are the only reason this file exists.
        """
        state: dict[str, bool] = {}
        for entry in self.entries():
            if entry.event in _OPEN:
                state[entry.job] = True
            elif entry.closes:
                state[entry.job] = False
        return tuple(sorted(job for job, open_now in state.items() if open_now))

    def boards_touched(self) -> tuple[str, ...]:
        boards = {str(entry.detail["board"]) for entry in self.entries() if "board" in entry.detail}
        return tuple(sorted(boards))

    def rotate(self, keep_lines: int = 5000) -> int:
        """Trim the file to its last lines, returning how many went.

        Called by the daily report rather than on a timer, because a journal
        that rotates while a job is running is a journal missing the lines
        that matter.
        """
        if keep_lines < 1:
            raise ValidationError("keep some lines", field="keep_lines", given=keep_lines)
        if not self.path.is_file():
            return 0
        lines = [
            line for line in self.path.read_text(encoding="utf-8").splitlines() if line.strip()
        ]
        if len(lines) <= keep_lines:
            return 0
        kept = lines[-keep_lines:]
        self.path.write_text("\n".join(kept) + "\n", encoding="utf-8")
        return len(lines) - len(kept)
