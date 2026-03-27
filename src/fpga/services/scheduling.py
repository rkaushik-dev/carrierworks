"""The waiting list, and the pass that hands boards out.

A request is somebody asking for a board by what it has to do rather than by
name. The queue exists because the honest answer to that is often "not yet":
every board that matches is busy, and the person asking would rather wait in a
line they can see than poll the bench themselves.

Ordering is priority first, then how long the request has waited, then the
order they arrived in. Fairness is the quota: one team cannot hold more than
its share while another team's request sits behind it, and a request from a
team at its quota is skipped rather than refused, because the quota will free
up on its own.

Dispatch takes the transaction with it. A request that gets a board and a
lease that gets written are the same commit, so a crash between them is not a
state anybody has to clean up.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from fpga.capabilities import RequirementSet
from fpga.config import BenchSettings
from fpga.errors import ValidationError
from fpga.persistence.unit import UnitOfWork
from fpga.scheduling.matcher import choose
from fpga.scheduling.queue import Request
from fpga.services import leasing

__all__ = [
    "DispatchOutcome",
    "cancel",
    "dispatch",
    "submit",
    "waiting",
]


@dataclass(frozen=True, slots=True)
class DispatchOutcome:
    """What one pass over the queue did, and what it could not do."""

    assigned: tuple[tuple[str, str, str], ...] = ()
    skipped: dict[str, str] | None = None

    @property
    def refusals(self) -> dict[str, str]:
        return dict(self.skipped or {})

    def describe(self) -> str:
        if not self.assigned and not self.refusals:
            return "nothing waiting"
        lines = [f"{request} -> {board} as {lease}" for request, board, lease in self.assigned]
        lines += [f"{request} waits: {why}" for request, why in sorted(self.refusals.items())]
        return "; ".join(lines)


def submit(
    unit: UnitOfWork,
    holder: str,
    purpose: str,
    requirements: str | list[str],
    now: datetime,
    *,
    priority: int = 5,
    minutes: int = 60,
    preemptible: bool = True,
    needs_hands: bool = False,
) -> Request:
    """Put a request on the waiting list."""
    wanted = RequirementSet.parse(requirements)
    request = Request(
        id=unit.requests.next_id(),
        holder=holder,
        purpose=purpose,
        requirements=wanted,
        submitted_at=now,
        priority=priority,
        minutes=minutes,
        preemptible=preemptible,
        needs_hands=needs_hands,
    )
    return unit.requests.add(request)


def cancel(unit: UnitOfWork, request_id: str, now: datetime) -> Request:
    """Take a request off the list."""
    return unit.requests.save(unit.requests.get(request_id).cancel(now))


def waiting(unit: UnitOfWork, now: datetime) -> tuple[Request, ...]:
    """Everything still waiting, in the order it will be served."""
    return unit.requests.ordered(now)


def _over_quota(unit: UnitOfWork, request: Request, now: datetime, quota: int | None) -> str | None:
    if quota is None:
        return None
    held = len(unit.leases.held_by(request.holder, now))
    if held < quota:
        return None
    return f"{request.holder} already holds {held} boards, quota is {quota}"


def dispatch(
    unit: UnitOfWork,
    now: datetime,
    *,
    settings: BenchSettings | None = None,
    quotas: dict[str, int] | None = None,
    limit: int = 0,
) -> DispatchOutcome:
    """One pass over the waiting list, taking every board it can.

    Boards are taken out of the pool as they are handed out, so two requests
    in one pass never both get the same board. That is the bug this being one
    function rather than a loop over a scheduler avoids.
    """
    live = settings or BenchSettings()
    if limit < 0:
        raise ValidationError("a dispatch limit is not negative", field="limit", given=limit)
    leasing.sweep_expired(unit, now)
    free = {board.tag: board for board in unit.boards.available()}
    assigned: list[tuple[str, str, str]] = []
    skipped: dict[str, str] = {}
    for request in unit.requests.ordered(now):
        if limit and len(assigned) >= limit:
            skipped[request.id] = "not reached on this pass"
            continue
        refusal = _over_quota(unit, request, now, (quotas or {}).get(request.holder))
        if refusal is not None:
            skipped[request.id] = refusal
            continue
        board = choose(tuple(free.values()), request.requirements, now=now)
        if board is None:
            skipped[request.id] = "nothing free matches what it asked for"
            continue
        lease = leasing.acquire(
            unit,
            board.tag,
            request.holder,
            request.purpose,
            now,
            minutes=request.minutes,
            priority=request.priority,
            preemptible=request.preemptible,
            settings=live,
        )
        unit.requests.save(request.satisfy(now), board=board.tag)
        del free[board.tag]
        assigned.append((request.id, board.tag, lease.id))
    return DispatchOutcome(assigned=tuple(assigned), skipped=skipped)
