"""Holding the leases, and the one rule that makes them worth having.

One active lease per board. Everything else here is bookkeeping around that
single invariant, and every method that could break it checks the same way, by
asking for the board's current lease rather than by trusting an index.

Expiry is a sweep rather than a background clock. Nothing in the bench runs
continuously except the supervisor, so leases go stale silently and are
collected when somebody next asks. That means every read has to treat an
expired lease as gone, which the lease itself already does, and the sweep only
exists to write the closure down.
"""

from __future__ import annotations

from datetime import datetime, timedelta

from fpga.errors import Conflict, NotFound, ValidationError
from fpga.ids import AssetTag, IdFactory, slug
from fpga.leases.models import Lease

__all__ = ["LeaseStore"]


class LeaseStore:
    """Every lease the bench has issued, live and closed."""

    def __init__(self, *, ceiling_minutes: int = 480, start: int = 1) -> None:
        if ceiling_minutes <= 0:
            raise ValidationError("a lease ceiling is some minutes", field="ceiling_minutes")
        self.ceiling_minutes = ceiling_minutes
        self._leases: dict[str, Lease] = {}
        self._ids = IdFactory("lease", start=start)

    def acquire(
        self,
        board: str,
        holder: str,
        purpose: str,
        now: datetime,
        *,
        minutes: int = 60,
        priority: int = 5,
        preemptible: bool = True,
    ) -> Lease:
        """Take a board, or refuse because somebody else has it."""
        if minutes <= 0:
            raise ValidationError("a lease lasts some minutes", field="minutes", given=minutes)
        if minutes > self.ceiling_minutes:
            raise ValidationError(
                "that is longer than a lease may run",
                field="minutes",
                given=minutes,
                ceiling=self.ceiling_minutes,
            )
        tag = str(AssetTag.parse(board))
        held = self.for_board(tag, now)
        if held is not None:
            raise Conflict(
                "that board is already leased",
                board=tag,
                holder=held.holder,
                lease=held.id,
                until=held.expires_at.isoformat(timespec="minutes"),
            )
        lease = Lease(
            id=self._ids.next(),
            board=tag,
            holder=holder,
            purpose=purpose,
            taken_at=now,
            expires_at=now + timedelta(minutes=minutes),
            priority=priority,
            preemptible=preemptible,
        )
        self._leases[lease.id] = lease
        return lease

    def lease(self, lease_id: str) -> Lease:
        found = self._leases.get(slug(lease_id))
        if found is None:
            raise NotFound("lease", slug(lease_id))
        return found

    def release(self, lease_id: str, now: datetime, reason: str = "released") -> Lease:
        closed = self.lease(lease_id).release(now, reason)
        self._leases[closed.id] = closed
        return closed

    def renew(self, lease_id: str, now: datetime, minutes: int) -> Lease:
        renewed = self.lease(lease_id).renewed(
            now,
            minutes,
            ceiling_minutes=self.ceiling_minutes,
        )
        self._leases[renewed.id] = renewed
        return renewed

    def for_board(self, board: str, now: datetime) -> Lease | None:
        """The lease holding this board right now, if there is one."""
        tag = str(AssetTag.parse(board))
        for lease in self._leases.values():
            if lease.board == tag and lease.active(now):
                return lease
        return None

    def held_by(self, holder: str, now: datetime) -> tuple[Lease, ...]:
        wanted = slug(holder)
        return tuple(
            lease for lease in self._sorted() if lease.holder == wanted and lease.active(now)
        )

    def active(self, now: datetime) -> tuple[Lease, ...]:
        return tuple(lease for lease in self._sorted() if lease.active(now))

    def all(self) -> tuple[Lease, ...]:
        return self._sorted()

    def history(self, board: str) -> tuple[Lease, ...]:
        tag = str(AssetTag.parse(board))
        return tuple(lease for lease in self._sorted() if lease.board == tag)

    def sweep(self, now: datetime) -> tuple[Lease, ...]:
        """Close every lease that has run out, and say which those were."""
        closed = []
        for lease in self._sorted():
            if not lease.released and lease.expired(now):
                expired = lease.expire(now)
                self._leases[expired.id] = expired
                closed.append(expired)
        return tuple(closed)

    def boards_held(self, now: datetime) -> tuple[str, ...]:
        return tuple(sorted({lease.board for lease in self.active(now)}))

    def counts_by_holder(self, now: datetime) -> dict[str, int]:
        """How many boards each holder has, which is what a quota reads."""
        counts: dict[str, int] = {}
        for lease in self.active(now):
            counts[lease.holder] = counts.get(lease.holder, 0) + 1
        return counts

    def _sorted(self) -> tuple[Lease, ...]:
        return tuple(sorted(self._leases.values(), key=lambda lease: (lease.taken_at, lease.id)))

    def __len__(self) -> int:
        return len(self._leases)
