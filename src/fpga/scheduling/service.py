"""The scheduler: the queue, the inventory and the leases in one place.

Everything it does happens on a tick. Somebody calls dispatch, it walks the
queue in order, and for each request it tries to find a board. Nothing runs in
the background, because a bench that schedules while nobody is looking is a
bench that cannot be reasoned about from its logs.

Order matters and it is not first come first served. The queue decides who is
looked at first; this decides whether they can be served, and a request that
cannot be is left where it is rather than being dropped or pushed back. Three
things stop a request being served: the bench is shut and the request needs
hands, the holder is already at their quota, or nothing free matches. Only the
last one is worth trying preemption for.

A refusal is recorded with its reason for every request that is not served, so
that "why is my job still queued" has an answer in the same tick that made the
decision.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime

from fpga.benchcalendar import BenchCalendar
from fpga.errors import Conflict, ValidationError
from fpga.inventory.book import BoardBook
from fpga.inventory.profile import board_profile
from fpga.leases.models import Lease
from fpga.leases.store import LeaseStore
from fpga.scheduling.matcher import choose
from fpga.scheduling.preemption import PreemptionPolicy
from fpga.scheduling.queue import Request, RequestQueue

__all__ = [
    "Assignment",
    "DispatchResult",
    "Scheduler",
]


@dataclass(frozen=True, slots=True)
class Assignment:
    """One request that got a board."""

    request: str
    board: str
    lease: Lease
    preempted: Lease | None = None

    def describe(self) -> str:
        line = f"{self.request} got {self.board} on {self.lease.id}"
        if self.preempted is not None:
            return f"{line}, taken from {self.preempted.holder}"
        return line


@dataclass(frozen=True, slots=True)
class DispatchResult:
    """What one tick of the scheduler did."""

    assignments: tuple[Assignment, ...] = ()
    refusals: dict[str, str] = field(default_factory=dict)

    @property
    def served(self) -> int:
        return len(self.assignments)

    def reason_for(self, request_id: str) -> str | None:
        return self.refusals.get(request_id)

    def describe(self) -> str:
        if not self.assignments and not self.refusals:
            return "nothing waiting"
        parts = [assignment.describe() for assignment in self.assignments]
        parts += [f"{request}: {reason}" for request, reason in sorted(self.refusals.items())]
        return "; ".join(parts)


class Scheduler:
    """Hands boards out, one tick at a time."""

    def __init__(
        self,
        book: BoardBook,
        leases: LeaseStore,
        queue: RequestQueue,
        *,
        policy: PreemptionPolicy | None = None,
        calendar: BenchCalendar | None = None,
        quotas: dict[str, int] | None = None,
    ) -> None:
        self.book = book
        self.leases = leases
        self.queue = queue
        self.policy = policy or PreemptionPolicy()
        self.calendar = calendar
        self.quotas = dict(quotas or {})

    def dispatch(self, now: datetime, *, faults: dict[str, int] | None = None) -> DispatchResult:
        """One pass over the queue."""
        self.leases.sweep(now)
        assignments: list[Assignment] = []
        refusals: dict[str, str] = {}
        for request in self.queue.ordered(self.leases.counts_by_holder(now)):
            refusal = self._blocked(request, now)
            if refusal is not None:
                refusals[request.id] = refusal
                continue
            assignment = self._serve(request, now, faults or {})
            if isinstance(assignment, str):
                refusals[request.id] = assignment
            else:
                assignments.append(assignment)
        return DispatchResult(assignments=tuple(assignments), refusals=refusals)

    def _blocked(self, request: Request, now: datetime) -> str | None:
        if request.needs_hands and self.calendar is not None:
            shut = self.calendar.blocked(now)
            if shut is not None:
                return f"needs somebody in the lab and {shut}"
        quota = self.quotas.get(request.holder)
        if quota is not None:
            held = len(self.leases.held_by(request.holder, now))
            if held >= quota:
                return f"{request.holder} already holds {held} boards, quota is {quota}"
        return None

    def _serve(self, request: Request, now: datetime, faults: dict[str, int]) -> Assignment | str:
        free = self.book.available()
        last_used = self._last_used()
        board = choose(free, request.requirements, now=now, last_used=last_used, faults=faults)
        preempted: Lease | None = None
        if board is None:
            taken = self._preempt_for(request, now)
            if taken is None:
                return "nothing free matches what it asked for"
            preempted = taken
            board = self.book.board(taken.board)
        try:
            lease = self.leases.acquire(
                board.tag,
                request.holder,
                request.purpose,
                now,
                minutes=request.minutes,
                priority=request.priority,
                preemptible=request.preemptible,
            )
        except Conflict as clash:  # pragma: no cover - the sweep should prevent this
            return clash.message
        self.book.move_to(board.tag, "leased", now, actor=request.holder)
        self.queue.satisfy(request.id, now)
        return Assignment(
            request=request.id,
            board=board.tag,
            lease=lease,
            preempted=preempted,
        )

    def _preempt_for(self, request: Request, now: datetime) -> Lease | None:
        """Take a board off somebody, if the rules allow it and one matches."""
        wanted = request.requirements
        candidates = []
        for lease in self.leases.active(now):
            if not self.book.has(lease.board):
                continue
            board = self.book.board(lease.board)
            if wanted.satisfied_by(board_profile(board)):
                candidates.append(lease)
        states = {board.tag: self.book.state(board.tag).state for board in self.book.all()}
        taken = self.policy.choose(
            tuple(candidates),
            now,
            claimant_priority=request.priority,
            board_states=states,
        )
        if taken is None:
            return None
        self.leases.release(taken.id, now, "preempted")
        self.book.move_to(taken.board, "available", now, actor="scheduler")
        return taken

    def _last_used(self) -> dict[str, datetime]:
        used: dict[str, datetime] = {}
        for lease in self.leases.all():
            end = lease.released_at or lease.expires_at
            if lease.board not in used or end > used[lease.board]:
                used[lease.board] = end
        return used

    def release(self, lease_id: str, now: datetime, reason: str = "released") -> Lease:
        """Give a board back and put it into service again."""
        lease = self.leases.release(lease_id, now, reason)
        if self.book.has(lease.board):
            state = self.book.state(lease.board).state
            if state in ("leased", "programming"):
                if state == "programming":
                    self.book.move_to(lease.board, "leased", now, actor=lease.holder)
                self.book.move_to(lease.board, "available", now, actor=lease.holder)
        return lease

    def fault(self, board: str, now: datetime, reason: str) -> None:
        """Take a board out of service, releasing whatever holds it."""
        if not reason.strip():
            raise ValidationError("a fault needs a reason", field="reason")
        held = self.leases.for_board(board, now)
        if held is not None:
            self.leases.release(held.id, now, "board faulted")
        if self.book.state(board).state != "faulted":
            self.book.move_to(board, "faulted", now, reason=reason, actor="scheduler")
