"""Taking a board off somebody who is using it.

This is the part of the scheduler that makes people angry, so the rules are
narrow and every refusal says which one applied.

A lease can only be preempted if its holder said it could be. A release
verification run marks itself unpreemptible and that is the end of the
conversation, whatever anybody else's priority is.

The claimant has to beat the holder by a margin rather than by a point.
Priority five taking a board off priority six all afternoon is not
prioritisation, it is churn.

A lease that has only just started is left alone, because the cost of
preempting is whatever work has been done so far and there is a minimum below
which it is not worth anybody's time. A lease that is nearly over is also left
alone, for the same reason from the other end: waiting four minutes is cheaper
than throwing away twenty.

And a board in the middle of being programmed is never taken, at any priority,
because interrupting a flash write leaves a device that will not boot.

Thermal is the exception to all of it. A board over its critical temperature
comes off its holder immediately, because the alternative is damage.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from fpga.errors import ValidationError
from fpga.leases.models import Lease

__all__ = [
    "PreemptionDecision",
    "PreemptionPolicy",
]


@dataclass(frozen=True, slots=True)
class PreemptionDecision:
    """Whether a lease may be taken, and why not."""

    lease: str
    board: str
    blockers: tuple[str, ...] = ()
    emergency: bool = False

    @property
    def allowed(self) -> bool:
        return not self.blockers

    def describe(self) -> str:
        if self.allowed:
            kind = "emergency" if self.emergency else "preemption"
            return f"{self.lease} on {self.board} may be taken ({kind})"
        return f"{self.lease} on {self.board} stays: " + "; ".join(self.blockers)


@dataclass(frozen=True, slots=True)
class PreemptionPolicy:
    """The margins the lab settled on after the first month of arguments."""

    margin: int = 2
    minimum_held: timedelta = timedelta(minutes=5)
    minimum_left: timedelta = timedelta(minutes=5)

    def __post_init__(self) -> None:
        if self.margin < 1:
            raise ValidationError("a margin of nothing is not a margin", field="margin")
        if self.minimum_held < timedelta(0) or self.minimum_left < timedelta(0):
            raise ValidationError("the minimums are not negative", field="policy")

    def evaluate(
        self,
        lease: Lease,
        now: datetime,
        *,
        claimant_priority: int,
        board_state: str = "leased",
        emergency: bool = False,
    ) -> PreemptionDecision:
        """Decide whether this lease can be taken for a claimant."""
        if emergency:
            return PreemptionDecision(lease=lease.id, board=lease.board, emergency=True)
        blockers: list[str] = []
        if not lease.active(now):
            blockers.append("that lease is not held any more")
            return PreemptionDecision(lease=lease.id, board=lease.board, blockers=tuple(blockers))
        if board_state == "programming":
            blockers.append("the board is being programmed")
        if not lease.preemptible:
            blockers.append("the holder marked the lease unpreemptible")
        if claimant_priority > lease.priority - self.margin:
            blockers.append(
                f"priority {claimant_priority} does not beat {lease.priority} by {self.margin}"
            )
        held = now - lease.taken_at
        if held < self.minimum_held:
            blockers.append(f"the lease is only {int(held.total_seconds() // 60)} minutes old")
        left = lease.remaining(now)
        if left <= self.minimum_left:
            blockers.append(f"it has {int(left.total_seconds() // 60)} minutes left to run")
        return PreemptionDecision(lease=lease.id, board=lease.board, blockers=tuple(blockers))

    def choose(
        self,
        leases: tuple[Lease, ...],
        now: datetime,
        *,
        claimant_priority: int,
        board_states: dict[str, str] | None = None,
    ) -> Lease | None:
        """The lease to take, out of everything that could be taken.

        The cheapest one, which means the lowest priority holder and then the
        one that has been running the shortest time, because that is the least
        work thrown away.
        """
        states = board_states or {}
        takeable = [
            lease
            for lease in leases
            if self.evaluate(
                lease,
                now,
                claimant_priority=claimant_priority,
                board_state=states.get(lease.board, "leased"),
            ).allowed
        ]
        if not takeable:
            return None
        return min(
            takeable,
            key=lambda lease: (-lease.priority, -lease.taken_at.timestamp(), lease.id),
        )
