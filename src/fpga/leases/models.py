"""A lease on a board: who, why, until when, and how firmly.

Everything about a lease is a compromise between two failure modes. Too short
and a nightly regression loses its board halfway through a run because somebody
set the default to twenty minutes. Too long and a board sits idle all weekend
because a job crashed on Friday afternoon holding it.

So a lease expires, and it can be renewed while its holder is still alive, and
there is a ceiling on how far renewal can push it. A job that has held a board
for eight hours is not a job any more, it is a leak.

Priority and preemptibility are separate on purpose. Priority decides who gets
a free board first. Preemptibility decides whether a held board can be taken
away, and a low priority job that is halfway through a two hour capture is
still not worth interrupting.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime, timedelta

from fpga.errors import ValidationError
from fpga.ids import AssetTag, slug

__all__ = [
    "MAX_PRIORITY",
    "MIN_PRIORITY",
    "Lease",
]

# One is the most urgent, nine is the least. The scale is small because
# anything wider turns into everybody picking two.
MIN_PRIORITY = 1
MAX_PRIORITY = 9


@dataclass(frozen=True, slots=True)
class Lease:
    """One hold on one board."""

    id: str
    board: str
    holder: str
    purpose: str
    taken_at: datetime
    expires_at: datetime
    priority: int = 5
    renewals: int = 0
    preemptible: bool = True
    released_at: datetime | None = None
    release_reason: str = ""

    def __post_init__(self) -> None:
        object.__setattr__(self, "id", slug(self.id))
        object.__setattr__(self, "board", str(AssetTag.parse(self.board)))
        object.__setattr__(self, "holder", slug(self.holder))
        if not self.purpose.strip():
            raise ValidationError("a lease says what it is for", field="purpose")
        for moment in (self.taken_at, self.expires_at):
            if moment.tzinfo is None:
                raise ValidationError("lease times are in utc", field="lease")
        if self.expires_at <= self.taken_at:
            raise ValidationError("a lease expires after it is taken", field="expires_at")
        if not MIN_PRIORITY <= self.priority <= MAX_PRIORITY:
            raise ValidationError("priority is one to nine", field="priority", given=self.priority)
        if self.renewals < 0:
            raise ValidationError("renewals are counted up", field="renewals")
        if self.released_at is not None and self.released_at < self.taken_at:
            raise ValidationError("a lease cannot be released before it was taken", field="lease")

    @property
    def released(self) -> bool:
        return self.released_at is not None

    def expired(self, now: datetime) -> bool:
        return now >= self.expires_at

    def active(self, now: datetime) -> bool:
        """Held right now, which means not released and not run out."""
        return not self.released and not self.expired(now)

    def remaining(self, now: datetime) -> timedelta:
        if not self.active(now):
            return timedelta(0)
        return self.expires_at - now

    def held_for(self, now: datetime) -> timedelta:
        end = self.released_at or min(now, self.expires_at)
        return max(end - self.taken_at, timedelta(0))

    def total_length(self) -> timedelta:
        return self.expires_at - self.taken_at

    def renewed(
        self,
        now: datetime,
        minutes: int,
        *,
        ceiling_minutes: int = 480,
    ) -> Lease:
        """Push the expiry out, up to the ceiling measured from the start.

        Renewal happens from now rather than from the old expiry, because a
        job that renews late has been running all that time and the point of
        the ceiling is to bound how long a board can be held.
        """
        if self.released:
            raise ValidationError("a released lease cannot be renewed", field="lease", id=self.id)
        if minutes <= 0:
            raise ValidationError("renew for some minutes", field="minutes", given=minutes)
        wanted = now + timedelta(minutes=minutes)
        ceiling = self.taken_at + timedelta(minutes=ceiling_minutes)
        if now > ceiling:
            raise ValidationError(
                "this lease has reached its ceiling",
                field="lease",
                id=self.id,
                ceiling_minutes=ceiling_minutes,
            )
        return replace(
            self,
            expires_at=min(wanted, ceiling),
            renewals=self.renewals + 1,
        )

    def release(self, now: datetime, reason: str = "released") -> Lease:
        if self.released:
            raise ValidationError("that lease is already released", field="lease", id=self.id)
        return replace(self, released_at=now, release_reason=reason)

    def expire(self, now: datetime) -> Lease:
        """Close a lease that ran out, keeping the expiry as the release time."""
        if self.released:
            return self
        return replace(self, released_at=self.expires_at, release_reason="expired")

    def outranks(self, other: Lease) -> bool:
        """Whether this lease's holder would win a contest for a board."""
        return self.priority < other.priority

    def describe(self) -> str:
        line = f"{self.id} {self.board} held by {self.holder} for {self.purpose}"
        if self.released:
            return f"{line}, released ({self.release_reason})"
        return f"{line}, until {self.expires_at.isoformat(timespec='minutes')}"
