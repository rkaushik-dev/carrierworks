"""The programming job itself: what was asked for and what became of it.

Three modes, and they are genuinely different operations rather than a flag on
one. Loading over JTAG puts an image straight into the device and it is gone at
the next power cycle, which is what a regression wants. Writing the update
region of the flash changes what the board boots into. Writing the golden
region changes what it falls back to when an update is bad, which is the
operation nobody should be able to do by accident.

A job carries its attempts rather than being retried by something outside it.
The count is on the record because the third attempt failing is a different
event from the first one failing, and only the record knows which this is.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime, timedelta

from fpga.errors import ValidationError
from fpga.ids import slug
from fpga.records import board_key

__all__ = [
    "MODES",
    "STATES",
    "ProgramJob",
]

MODES: tuple[str, ...] = ("jtag", "flash-update", "flash-golden")
STATES: tuple[str, ...] = ("queued", "running", "passed", "failed", "cancelled")

_FINISHED = frozenset({"passed", "failed", "cancelled"})


@dataclass(frozen=True, slots=True)
class ProgramJob:
    """One attempt to put one image on one board."""

    id: str
    board: str
    digest: str
    mode: str
    requested_by: str
    created_at: datetime
    state: str = "queued"
    attempts: int = 0
    verify: bool = True
    started_at: datetime | None = None
    finished_at: datetime | None = None
    error: str = ""
    error_code: str = ""
    clocks: int = 0
    lease: str = ""

    def __post_init__(self) -> None:
        object.__setattr__(self, "id", slug(self.id))
        object.__setattr__(self, "board", board_key(self.board))
        object.__setattr__(self, "requested_by", slug(self.requested_by))
        object.__setattr__(self, "mode", slug(self.mode))
        if self.mode not in MODES:
            raise ValidationError("no such programming mode", field="mode", given=self.mode)
        if self.state not in STATES:
            raise ValidationError("no such job state", field="state", given=self.state)
        if len(self.digest) != 64:
            raise ValidationError("a job carries a full digest", field="digest")
        if self.created_at.tzinfo is None:
            raise ValidationError("job times are in utc", field="created_at")
        if self.attempts < 0:
            raise ValidationError("attempts are counted up", field="attempts")

    @property
    def finished(self) -> bool:
        return self.state in _FINISHED

    @property
    def succeeded(self) -> bool:
        return self.state == "passed"

    @property
    def writes_flash(self) -> bool:
        return self.mode.startswith("flash")

    @property
    def touches_golden(self) -> bool:
        """The one mode that can leave a board unable to boot at all."""
        return self.mode == "flash-golden"

    @property
    def short_digest(self) -> str:
        return self.digest[:12]

    def duration(self) -> timedelta | None:
        if self.started_at is None or self.finished_at is None:
            return None
        return self.finished_at - self.started_at

    def start(self, now: datetime, *, lease: str = "") -> ProgramJob:
        if self.finished:
            raise ValidationError("that job is over", field="job", id=self.id)
        if self.state == "running":
            raise ValidationError("that job is already running", field="job", id=self.id)
        return replace(
            self,
            state="running",
            attempts=self.attempts + 1,
            started_at=self.started_at or now,
            lease=lease or self.lease,
        )

    def passed(self, now: datetime, *, clocks: int = 0) -> ProgramJob:
        if self.state != "running":
            raise ValidationError("a job passes from running", field="job", id=self.id)
        return replace(self, state="passed", finished_at=now, clocks=clocks, error="")

    def failed(
        self, now: datetime, reason: str, *, code: str = "", retryable: bool = False
    ) -> ProgramJob:
        """Record a failure, and go back to queued when it is worth retrying."""
        if self.state != "running":
            raise ValidationError("a job fails from running", field="job", id=self.id)
        if not reason.strip():
            raise ValidationError("a failure has a reason", field="reason")
        return replace(
            self,
            state="queued" if retryable else "failed",
            finished_at=None if retryable else now,
            error=reason,
            error_code=code,
        )

    def cancel(self, now: datetime, reason: str = "cancelled") -> ProgramJob:
        if self.finished:
            raise ValidationError("that job is over", field="job", id=self.id)
        return replace(self, state="cancelled", finished_at=now, error=reason)

    def exhausted(self, limit: int) -> bool:
        return self.attempts >= limit

    def describe(self) -> str:
        line = f"{self.id} {self.mode} {self.short_digest} on {self.board} [{self.state}]"
        if self.error:
            return f"{line} {self.error}"
        return line
