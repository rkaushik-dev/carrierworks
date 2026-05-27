"""Taking a board, giving it back, and keeping it a bit longer.

The lease and the board state are two rows that have to agree: a leased board
says leased, an available one has no live lease. Every function here writes
both, in one transaction, which is the whole reason the unit of work exists.

Lease numbers are derived from what is already stored rather than from a
counter in memory. A restart in the middle of a busy afternoon would otherwise
start again at one and collide with everything issued that morning.
"""

from __future__ import annotations

from datetime import datetime, timedelta

from fpga.config import BenchSettings
from fpga.errors import Conflict, ValidationError
from fpga.leases.models import Lease
from fpga.persistence.unit import UnitOfWork
from fpga.services import audit

__all__ = [
    "acquire",
    "expiring",
    "remind",
    "next_lease_id",
    "release",
    "renew",
    "sweep_expired",
]

_PREFIX = "lease"
_WIDTH = 5


def next_lease_id(unit: UnitOfWork) -> str:
    """The next lease number, carried on from whatever is in the database."""
    highest = 0
    for board in unit.boards.all():
        for lease in unit.leases.history(board.tag):
            highest = max(highest, _number_of(lease.id))
    return f"{_PREFIX}-{highest + 1:0{_WIDTH}d}"


def _number_of(lease_id: str) -> int:
    tail = lease_id.rsplit("-", 1)[-1]
    return int(tail) if tail.isdigit() else 0


def acquire(
    unit: UnitOfWork,
    tag: str,
    holder: str,
    purpose: str,
    now: datetime,
    *,
    minutes: int | None = None,
    priority: int = 5,
    preemptible: bool = True,
    settings: BenchSettings | None = None,
) -> Lease:
    """Take a board, refusing if it is busy or out of service."""
    live = settings or BenchSettings()
    length = minutes or live.default_lease_minutes
    if length <= 0:
        raise ValidationError("a lease lasts some minutes", field="minutes", given=length)
    if length > live.max_lease_minutes:
        raise ValidationError(
            "that is longer than a lease may run",
            field="minutes",
            given=length,
            ceiling=live.max_lease_minutes,
        )
    # The lease is checked before the state so that a board somebody else has
    # says who has it. Reporting only that it is not available is true and
    # sends the asker to look at the rack for no reason.
    held = unit.leases.for_board(tag, now)
    if held is not None:
        raise Conflict("that board is already leased", board=tag, holder=held.holder)
    state = unit.boards.state(tag)
    if not state.usable:
        raise Conflict("that board is not available", board=tag, state=state.state)
    lease = Lease(
        id=next_lease_id(unit),
        board=tag,
        holder=holder,
        purpose=purpose,
        taken_at=now,
        expires_at=now + timedelta(minutes=length),
        priority=priority,
        preemptible=preemptible,
    )
    unit.leases.add(lease)
    unit.boards.save_state(tag, state.move("leased", now, actor=lease.holder))
    audit.record(
        unit,
        now,
        "lease-taken",
        actor=lease.holder,
        subject=lease.board,
        detail=f"{lease.id} for {lease.purpose}",
    )
    return lease


def release(unit: UnitOfWork, lease_id: str, now: datetime, reason: str = "released") -> Lease:
    """Give a board back and put it into service again."""
    lease = unit.leases.get(lease_id)
    closed = lease.release(now, reason)
    unit.leases.save(closed)
    state = unit.boards.state(lease.board)
    if state.state == "programming":
        state = state.move("leased", now, actor=lease.holder)
        unit.boards.save_state(lease.board, state)
    if state.state == "leased":
        unit.boards.save_state(lease.board, state.move("available", now, actor=lease.holder))
    audit.record(
        unit,
        now,
        "lease-released",
        actor=lease.holder,
        subject=lease.board,
        detail=f"{lease.id} {reason}",
    )
    return closed


def renew(
    unit: UnitOfWork,
    lease_id: str,
    now: datetime,
    minutes: int,
    *,
    settings: BenchSettings | None = None,
) -> Lease:
    """Push a lease out, up to the ceiling measured from when it started."""
    live = settings or BenchSettings()
    renewed = unit.leases.get(lease_id).renewed(
        now,
        minutes,
        ceiling_minutes=live.max_lease_minutes,
    )
    unit.leases.save(renewed)
    return renewed


def sweep_expired(unit: UnitOfWork, now: datetime) -> tuple[Lease, ...]:
    """Close every lease that has run out and free the boards they held.

    Nothing runs this on a timer. The scheduler calls it at the top of every
    tick, which is often enough that a board is never held by a dead job for
    longer than one round.
    """
    closed = []
    for board in unit.boards.all():
        for lease in unit.leases.history(board.tag):
            if lease.released or not lease.expired(now):
                continue
            expired = lease.expire(now)
            unit.leases.save(expired)
            state = unit.boards.state(board.tag)
            if state.state in ("leased", "programming"):
                if state.state == "programming":
                    state = state.move("leased", now, actor="sweeper")
                    unit.boards.save_state(board.tag, state)
                unit.boards.save_state(
                    board.tag,
                    state.move("available", now, actor="sweeper"),
                )
            closed.append(expired)
    return tuple(closed)


def expiring(
    unit: UnitOfWork,
    now: datetime,
    within: timedelta = timedelta(minutes=15),
) -> tuple[Lease, ...]:
    """Leases that run out soon, soonest first.

    Soon is a window rather than a threshold because the interesting question
    is who to warn, and warning somebody about a lease with four hours left is
    how people learn to ignore the warnings.
    """
    if within <= timedelta(0):
        raise ValidationError("a warning window has some length", field="within")
    soon = [lease for lease in unit.leases.active(now) if lease.remaining(now) <= within]
    return tuple(sorted(soon, key=lambda lease: (lease.expires_at, lease.id)))


def remind(
    unit: UnitOfWork,
    now: datetime,
    within: timedelta = timedelta(minutes=15),
) -> tuple[Lease, ...]:
    """Write a warning into the trail for every lease about to run out.

    The trail is the only place the bench can leave a message for somebody who
    is not looking at it right now, and a swept lease that surprised its holder
    is the complaint this answers.
    """
    warned = expiring(unit, now, within)
    for lease in warned:
        minutes = int(lease.remaining(now).total_seconds() // 60)
        audit.record(
            unit,
            now,
            "expiring",
            subject=lease.board,
            detail=f"{lease.id} held by {lease.holder} runs out in {minutes} minutes",
        )
    return warned
