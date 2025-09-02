"""The steps that bring a board up, and the things that stop them.

A sequence is built and then run, the same way a flash plan is, and for the
same reason: somebody is going to want to see what is about to happen to a
board before it happens. The steps are small and boring on purpose. Enable a
rail, wait for it to settle, read it back, move on.

The read back is the step people leave out. A rail that has been enabled is not
a rail that is up; it is a rail whose regulator has been told to start. On the
carrier with the adjustable supply, the difference is twelve milliseconds and
one board where the strap was wrong for a fortnight.

Interlocks are checked once, before the first step, and any of them being
engaged means nothing runs. They are not steps because they are not things the
sequence does, they are conditions it needs, and mixing the two is how a
sequence ends up half executed with a rail up and an interlock engaged.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from fpga.errors import ValidationError
from fpga.ids import slug
from fpga.power.rails import PowerTree

__all__ = [
    "Interlock",
    "PowerStep",
    "SequencePlan",
    "power_down_plan",
    "power_up_plan",
]


@dataclass(frozen=True, slots=True)
class PowerStep:
    """One thing to do to one rail."""

    action: str
    rail: str
    millis: int = 0

    def __post_init__(self) -> None:
        if self.action not in ("enable", "disable", "settle", "check"):
            raise ValidationError("no such power step", field="action", given=self.action)
        if self.millis < 0:
            raise ValidationError("a step takes no negative time", field="millis")

    def describe(self) -> str:
        if self.millis:
            return f"{self.action} {self.rail} ({self.millis}ms)"
        return f"{self.action} {self.rail}"


@dataclass(frozen=True, slots=True)
class Interlock:
    """A condition that has to be clear before power is touched."""

    name: str
    engaged: bool
    reason: str = ""

    def __post_init__(self) -> None:
        object.__setattr__(self, "name", slug(self.name))

    def describe(self) -> str:
        return f"{self.name}: {self.reason}" if self.reason else self.name


@dataclass(frozen=True, slots=True)
class SequencePlan:
    """Everything to do, in order, and what would stop it."""

    model: str
    direction: str
    steps: tuple[PowerStep, ...]
    blockers: tuple[str, ...] = ()

    @property
    def runnable(self) -> bool:
        return not self.blockers

    @property
    def total_ms(self) -> int:
        return sum(step.millis for step in self.steps)

    def rails(self) -> tuple[str, ...]:
        seen: list[str] = []
        for step in self.steps:
            if step.rail not in seen:
                seen.append(step.rail)
        return tuple(seen)

    def steps_of(self, action: str) -> tuple[PowerStep, ...]:
        return tuple(step for step in self.steps if step.action == action)

    def describe(self) -> str:
        if not self.runnable:
            return f"{self.model} {self.direction} blocked: " + "; ".join(self.blockers)
        return f"{self.model} {self.direction}: {len(self.steps)} steps, {self.total_ms}ms"


def _blockers(interlocks: tuple[Interlock, ...]) -> tuple[str, ...]:
    return tuple(lock.describe() for lock in interlocks if lock.engaged)


def power_up_plan(
    tree: PowerTree,
    *,
    interlocks: tuple[Interlock, ...] = (),
    verify: bool = True,
) -> SequencePlan:
    """Bring a board up, rail by rail, in dependency order."""
    steps: list[PowerStep] = []
    for rail in tree.order_up():
        steps.append(PowerStep(action="enable", rail=rail.name))
        if rail.settle_ms:
            steps.append(PowerStep(action="settle", rail=rail.name, millis=rail.settle_ms))
        if verify:
            steps.append(PowerStep(action="check", rail=rail.name))
    return SequencePlan(
        model=tree.model,
        direction="up",
        steps=tuple(steps),
        blockers=_blockers(interlocks),
    )


def power_down_plan(
    tree: PowerTree,
    *,
    interlocks: tuple[Interlock, ...] = (),
    settle_ms: int = 2,
) -> SequencePlan:
    """Take a board down in the order the datasheets want.

    Interlocks apply here too, and that is not an oversight. The one that
    matters is maintenance: a board somebody has their hands inside is a board
    whose rails should not move in either direction without them saying so.
    """
    steps: list[PowerStep] = []
    for rail in tree.order_down():
        steps.append(PowerStep(action="disable", rail=rail.name))
        if settle_ms:
            steps.append(PowerStep(action="settle", rail=rail.name, millis=settle_ms))
    return SequencePlan(
        model=tree.model,
        direction="down",
        steps=tuple(steps),
        blockers=_blockers(interlocks),
    )


def interlocks_from(
    checks: dict[str, Callable[[], bool]],
    reasons: dict[str, str] | None = None,
) -> tuple[Interlock, ...]:
    """Build interlocks by asking each check, treating a failure as engaged.

    A check that raises is engaged, not clear. The supervisor cannot read the
    lid switch on one of the racks when its controller is rebooting, and the
    safe answer to that is that the lid is open.
    """
    said = reasons or {}
    built = []
    for name, check in checks.items():
        try:
            engaged = bool(check())
        except Exception:  # noqa: BLE001 - an unreadable interlock counts as engaged
            built.append(Interlock(name=name, engaged=True, reason="could not be read"))
            continue
        built.append(Interlock(name=name, engaged=engaged, reason=said.get(name, "")))
    return tuple(built)
