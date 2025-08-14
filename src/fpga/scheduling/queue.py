"""The waiting list, and the two rules that decide who is at the front of it.

Priority first, because some things genuinely matter more: a release
verification run beats somebody trying an experiment. Then fair share, which is
the rule that stops the nightly regression from holding every board on the
bench while three people wait behind it. Among requests of equal priority, the
team currently holding fewest boards goes first, and time waiting breaks the
remaining tie.

The queue does not know what a board is. It orders requests and hands them out
one at a time; whether anything can satisfy the one at the front is the
scheduler's problem, and a request that cannot be satisfied right now stays
where it is rather than being pushed to the back.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime, timedelta

from fpga.capabilities import RequirementSet
from fpga.errors import NotFound, ValidationError
from fpga.ids import IdFactory, slug
from fpga.leases.models import MAX_PRIORITY, MIN_PRIORITY

__all__ = [
    "Request",
    "RequestQueue",
]


@dataclass(frozen=True, slots=True)
class Request:
    """Somebody asking for a board they have not got yet."""

    id: str
    holder: str
    purpose: str
    requirements: RequirementSet
    submitted_at: datetime
    priority: int = 5
    minutes: int = 60
    preemptible: bool = True
    needs_hands: bool = False
    cancelled_at: datetime | None = None
    satisfied_at: datetime | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "id", slug(self.id))
        object.__setattr__(self, "holder", slug(self.holder))
        if not self.purpose.strip():
            raise ValidationError("a request says what it is for", field="purpose")
        if self.submitted_at.tzinfo is None:
            raise ValidationError("request times are in utc", field="submitted_at")
        if not MIN_PRIORITY <= self.priority <= MAX_PRIORITY:
            raise ValidationError("priority is one to nine", field="priority")
        if self.minutes <= 0:
            raise ValidationError("a request asks for some minutes", field="minutes")

    @property
    def open(self) -> bool:
        return self.cancelled_at is None and self.satisfied_at is None

    def waited(self, now: datetime) -> timedelta:
        end = self.satisfied_at or self.cancelled_at or now
        return max(end - self.submitted_at, timedelta(0))

    def cancel(self, now: datetime) -> Request:
        if not self.open:
            raise ValidationError("that request is already closed", field="request", id=self.id)
        return replace(self, cancelled_at=now)

    def satisfy(self, now: datetime) -> Request:
        if not self.open:
            raise ValidationError("that request is already closed", field="request", id=self.id)
        return replace(self, satisfied_at=now)

    def describe(self) -> str:
        return f"{self.id} {self.holder} priority {self.priority} for {self.purpose}"


class RequestQueue:
    """Requests waiting for a board."""

    def __init__(self, *, start: int = 1) -> None:
        self._requests: dict[str, Request] = {}
        self._ids = IdFactory("req", start=start)

    def submit(
        self,
        holder: str,
        purpose: str,
        requirements: RequirementSet,
        now: datetime,
        *,
        priority: int = 5,
        minutes: int = 60,
        preemptible: bool = True,
        needs_hands: bool = False,
    ) -> Request:
        request = Request(
            id=self._ids.next(),
            holder=holder,
            purpose=purpose,
            requirements=requirements,
            submitted_at=now,
            priority=priority,
            minutes=minutes,
            preemptible=preemptible,
            needs_hands=needs_hands,
        )
        self._requests[request.id] = request
        return request

    def request(self, request_id: str) -> Request:
        found = self._requests.get(slug(request_id))
        if found is None:
            raise NotFound("request", slug(request_id))
        return found

    def cancel(self, request_id: str, now: datetime) -> Request:
        cancelled = self.request(request_id).cancel(now)
        self._requests[cancelled.id] = cancelled
        return cancelled

    def satisfy(self, request_id: str, now: datetime) -> Request:
        """Mark a request as having got its board."""
        done = self.request(request_id).satisfy(now)
        self._requests[done.id] = done
        return done

    def all(self) -> tuple[Request, ...]:
        return tuple(sorted(self._requests.values(), key=lambda item: item.id))

    def pending(self) -> tuple[Request, ...]:
        return tuple(request for request in self.all() if request.open)

    def ordered(self, holdings: dict[str, int] | None = None) -> tuple[Request, ...]:
        """The queue in the order it should be served."""
        held = {slug(key): value for key, value in (holdings or {}).items()}
        return tuple(
            sorted(
                self.pending(),
                key=lambda request: (
                    request.priority,
                    held.get(request.holder, 0),
                    request.submitted_at,
                    request.id,
                ),
            )
        )

    def head(self, holdings: dict[str, int] | None = None) -> Request | None:
        ordered = self.ordered(holdings)
        return ordered[0] if ordered else None

    def position_of(self, request_id: str, holdings: dict[str, int] | None = None) -> int:
        """Where a request sits in the queue, counted from one.

        Zero means it is not waiting any more, which is a different answer
        from being at the front and worth not confusing with it.
        """
        wanted = self.request(request_id)
        if not wanted.open:
            return 0
        for index, request in enumerate(self.ordered(holdings), start=1):
            if request.id == wanted.id:
                return index
        return 0  # pragma: no cover - an open request is always in the order

    def waiting_for(self, holder: str) -> tuple[Request, ...]:
        wanted = slug(holder)
        return tuple(request for request in self.pending() if request.holder == wanted)

    def longest_wait(self, now: datetime) -> timedelta:
        waits = [request.waited(now) for request in self.pending()]
        return max(waits) if waits else timedelta(0)

    def __len__(self) -> int:
        return len(self._requests)
