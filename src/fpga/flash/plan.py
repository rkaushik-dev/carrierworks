"""Turning "put this image there" into the steps that actually do it.

A plan is built before anything is touched, and it is worth having as an object
for two reasons. It can be shown to somebody before it runs, which matters when
the thing about to be erased is the only working image on a board. And it
carries a time estimate, which is the difference between an operator waiting
and an operator pulling the cable at the four minute mark because nothing has
moved.

The estimates come out of the datasheet typicals and they are honest about
being typicals. A part near the end of its life erases slower, and the plan
does not pretend to know that.

The order is fixed and it is not negotiable: erase every sector the write will
touch, then program page by page, then read it all back if verification is on.
Programming into a sector that was not erased writes ones into zeros and only
zeros into ones, so the result is neither the old image nor the new one.
"""

from __future__ import annotations

from dataclasses import dataclass

from fpga.catalog.flashparts import FlashSpec
from fpga.errors import ValidationError
from fpga.flash.layout import FlashLayout
from fpga.measure import format_size

__all__ = [
    "FlashPlan",
    "FlashStep",
    "plan_erase",
    "plan_write",
]

_READ_CHUNK = 65536
# A read back runs at roughly this rate over the adapters the lab has.
_READ_BYTES_PER_MS = 1200


@dataclass(frozen=True, slots=True)
class FlashStep:
    """One thing to do to the part."""

    kind: str
    address: int
    length: int
    estimated_ms: int

    def describe(self) -> str:
        return f"{self.kind} 0x{self.address:08x} {format_size(self.length)} ~{self.estimated_ms}ms"


@dataclass(frozen=True, slots=True)
class FlashPlan:
    """Everything to do, in order, with what it is expected to cost."""

    part: FlashSpec
    region: str
    steps: tuple[FlashStep, ...]
    image_bytes: int

    @property
    def total_ms(self) -> int:
        return sum(step.estimated_ms for step in self.steps)

    @property
    def total_seconds(self) -> int:
        return -(-self.total_ms // 1000)

    def steps_of(self, kind: str) -> tuple[FlashStep, ...]:
        return tuple(step for step in self.steps if step.kind == kind)

    @property
    def erased_bytes(self) -> int:
        return sum(step.length for step in self.steps if step.kind.startswith("erase"))

    @property
    def programmed_bytes(self) -> int:
        return sum(step.length for step in self.steps_of("program"))

    @property
    def verifies(self) -> bool:
        return bool(self.steps_of("verify"))

    def describe(self) -> str:
        erases = len([step for step in self.steps if step.kind.startswith("erase")])
        programs = len(self.steps_of("program"))
        verifies = len(self.steps_of("verify"))
        return (
            f"{self.region} on {self.part.name}: {erases} erases, {programs} page writes, "
            f"{verifies} reads, about {self.total_seconds}s"
        )


def _erase_steps(part: FlashSpec, start: int, end: int, *, use_subsectors: bool) -> list[FlashStep]:
    """Erase steps covering everything from start up to end.

    Subsector erases are offered because rewriting a small parameter block with
    four kilobyte erases is much faster than with sixty four kilobyte ones. For
    a whole image it is slower, since a sector is sixteen subsectors and the
    per command overhead adds up, so the caller chooses.
    """
    steps: list[FlashStep] = []
    if use_subsectors:
        size = part.subsector_bytes
        cost = part.subsector_erase_ms
    else:
        size = part.sector_bytes
        cost = part.sector_erase_ms
    first = (start // size) * size
    address = first
    while address < end:
        steps.append(FlashStep(kind="erase", address=address, length=size, estimated_ms=cost))
        address += size
    return steps


def _program_steps(part: FlashSpec, start: int, data: bytes) -> list[FlashStep]:
    """Page writes, split at page boundaries rather than every page bytes.

    A write that crosses a page boundary wraps to the start of the same page
    inside the part, which corrupts silently, so the first write is short
    whenever the image does not begin on a page boundary.
    """
    steps: list[FlashStep] = []
    offset = 0
    while offset < len(data):
        address = start + offset
        room = part.page_bytes - (address % part.page_bytes)
        take = min(room, len(data) - offset)
        steps.append(
            FlashStep(
                kind="program",
                address=address,
                length=take,
                estimated_ms=max(1, part.page_program_us // 1000),
            )
        )
        offset += take
    return steps


def _verify_steps(start: int, length: int) -> list[FlashStep]:
    steps: list[FlashStep] = []
    offset = 0
    while offset < length:
        take = min(_READ_CHUNK, length - offset)
        steps.append(
            FlashStep(
                kind="verify",
                address=start + offset,
                length=take,
                estimated_ms=max(1, take // _READ_BYTES_PER_MS),
            )
        )
        offset += take
    return steps


def plan_write(
    layout: FlashLayout,
    region: str,
    data: bytes,
    *,
    verify: bool = True,
    use_subsectors: bool = False,
) -> FlashPlan:
    """The steps to put an image into a region of a part."""
    target = layout.region(region)
    if not data:
        raise ValidationError("nothing to program", field="data")
    if not target.holds(len(data)):
        raise ValidationError(
            "the image does not fit that region",
            field="data",
            region=target.name,
            image=len(data),
            room=target.length,
        )
    steps = _erase_steps(
        layout.part,
        target.start,
        target.start + len(data),
        use_subsectors=use_subsectors,
    )
    steps += _program_steps(layout.part, target.start, data)
    if verify:
        steps += _verify_steps(target.start, len(data))
    return FlashPlan(
        part=layout.part,
        region=target.name,
        steps=tuple(steps),
        image_bytes=len(data),
    )


def plan_erase(layout: FlashLayout, region: str, *, use_subsectors: bool = False) -> FlashPlan:
    """The steps to clear a whole region and leave it erased."""
    target = layout.region(region)
    steps = _erase_steps(
        layout.part,
        target.start,
        target.end,
        use_subsectors=use_subsectors,
    )
    return FlashPlan(part=layout.part, region=target.name, steps=tuple(steps), image_bytes=0)
