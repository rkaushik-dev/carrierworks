"""Where a thing physically is, and what that means for the things near it.

The bench is four racks of shelves, six slots to a shelf, and the slots are
numbered left to right as you face the rack. That much is bookkeeping. The part
that is not bookkeeping is airflow: the racks pull air from the bottom, so a
board running hot warms the slots above it and nothing below it. The thermal
supervisor asks this module which boards are downwind of a hot one, and it gets
a different answer than it would from a plain distance.

Power is a separate address space on purpose. A board in rack 2 can perfectly
well be fed from the distribution unit that lives in rack 3, because that is
where the free outlet was on the day somebody racked it.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from fpga.errors import ValidationError
from fpga.ids import slug

__all__ = [
    "SHELVES_PER_RACK",
    "SLOTS_PER_SHELF",
    "BenchLocation",
    "PduChannel",
    "downwind_of",
]

SHELVES_PER_RACK = 12
SLOTS_PER_SHELF = 6

_LOCATION = re.compile(
    r"""
    ^(?P<rack>[a-z0-9][a-z0-9-]*)
    [/:]
    (?:shelf-?)? (?P<shelf>\d{1,2})
    [/:]
    (?:slot-?)? (?P<slot>\d)
    $
    """,
    re.VERBOSE,
)

_PDU = re.compile(r"^(?P<pdu>[a-z0-9][a-z0-9-]*)[/:](?:outlet-?)?(?P<outlet>\d{1,2})$")


@dataclass(frozen=True, slots=True, order=True)
class BenchLocation:
    """A slot in a shelf in a rack."""

    rack: str
    shelf: int
    slot: int

    def __post_init__(self) -> None:
        object.__setattr__(self, "rack", slug(self.rack))
        if not 1 <= self.shelf <= SHELVES_PER_RACK:
            raise ValidationError("shelf out of range", field="shelf", given=self.shelf)
        if not 1 <= self.slot <= SLOTS_PER_SHELF:
            raise ValidationError("slot out of range", field="slot", given=self.slot)

    @classmethod
    def parse(cls, text: str) -> BenchLocation:
        cleaned = text.strip().lower().replace(" ", "")
        match = _LOCATION.match(cleaned)
        if match is None:
            raise ValidationError("cannot read a location from " + repr(text), field="location")
        return cls(
            rack=match.group("rack"),
            shelf=int(match.group("shelf")),
            slot=int(match.group("slot")),
        )

    @property
    def shelf_key(self) -> tuple[str, int]:
        return (self.rack, self.shelf)

    def same_shelf(self, other: BenchLocation) -> bool:
        return self.shelf_key == other.shelf_key

    def is_above(self, other: BenchLocation) -> bool:
        """Higher shelf number is higher off the floor."""
        return self.rack == other.rack and self.shelf > other.shelf

    def shelves_above(self, count: int) -> tuple[BenchLocation, ...]:
        """The shelves this one sits under, nearest first."""
        if count < 0:
            raise ValidationError("count must not be negative", field="count", given=count)
        top = min(self.shelf + count, SHELVES_PER_RACK)
        return tuple(
            BenchLocation(self.rack, shelf, self.slot) for shelf in range(self.shelf + 1, top + 1)
        )

    def __str__(self) -> str:
        return f"{self.rack}/shelf-{self.shelf:02d}/slot-{self.slot}"


@dataclass(frozen=True, slots=True, order=True)
class PduChannel:
    """One switched outlet on one distribution unit."""

    pdu: str
    outlet: int

    def __post_init__(self) -> None:
        object.__setattr__(self, "pdu", slug(self.pdu))
        if not 1 <= self.outlet <= 24:
            raise ValidationError("outlet out of range", field="outlet", given=self.outlet)

    @classmethod
    def parse(cls, text: str) -> PduChannel:
        cleaned = text.strip().lower().replace(" ", "")
        match = _PDU.match(cleaned)
        if match is None:
            raise ValidationError("cannot read a pdu channel from " + repr(text), field="pdu")
        return cls(pdu=match.group("pdu"), outlet=int(match.group("outlet")))

    def __str__(self) -> str:
        return f"{self.pdu}/outlet-{self.outlet:02d}"


def downwind_of(
    source: BenchLocation,
    candidates: dict[str, BenchLocation],
    *,
    reach: int = 2,
) -> tuple[str, ...]:
    """Which of ``candidates`` sit in the warm air coming off ``source``.

    Same rack, same slot column, and within ``reach`` shelves above. Returns
    the keys in the order the air reaches them, nearest first, which is also
    the order the supervisor wants to warn people in.
    """
    warmed = source.shelves_above(reach)
    order = {location: index for index, location in enumerate(warmed)}
    hits = [(order[location], key) for key, location in candidates.items() if location in order]
    return tuple(key for _, key in sorted(hits))
