"""What lives where in the configuration flash on a board.

A layout is a list of named regions with an address and a length, and the rules
it has to keep are all consequences of one fact: the smallest thing that can be
erased is a sector. Two regions inside one sector cannot be updated
independently, so a layout that lets that happen is a layout where updating the
test image destroys the golden one. That is checked here and refused, rather
than discovered on a board in a customer's rack.

A region is allowed to start part way through a sector. That looks wrong at
first and it is deliberate: a small parameter block tucked above an image is
normal, and the no shared sector rule already guarantees that erasing whatever
owns that sector cannot take anything else with it.

The golden image sits at zero because that is where the device looks after
power on. Everything else is somewhere the multiboot jump can reach, which is
any sector boundary in the part.
"""

from __future__ import annotations

from dataclasses import dataclass

from fpga.catalog.flashparts import FlashSpec
from fpga.errors import ConfigurationError, NotFound, ValidationError
from fpga.ids import slug
from fpga.measure import format_size

__all__ = [
    "FlashLayout",
    "FlashRegion",
    "multiboot_layout",
    "single_image_layout",
]

_PURPOSES = ("golden", "update", "data", "reserved")


@dataclass(frozen=True, slots=True)
class FlashRegion:
    """One named stretch of the part."""

    name: str
    start: int
    length: int
    purpose: str = "data"

    def __post_init__(self) -> None:
        object.__setattr__(self, "name", slug(self.name))
        if self.start < 0:
            raise ValidationError("a region starts inside the part", field="start")
        if self.length <= 0:
            raise ValidationError("a region has a length", field="length", given=self.length)
        if self.purpose not in _PURPOSES:
            raise ValidationError("no such region purpose", field="purpose", given=self.purpose)

    @property
    def end(self) -> int:
        return self.start + self.length

    def contains(self, address: int) -> bool:
        return self.start <= address < self.end

    def overlaps(self, other: FlashRegion) -> bool:
        return self.start < other.end and other.start < self.end

    def holds(self, size: int) -> bool:
        return 0 <= size <= self.length

    def describe(self) -> str:
        return f"{self.name} 0x{self.start:08x} +{format_size(self.length)} ({self.purpose})"


@dataclass(frozen=True, slots=True)
class FlashLayout:
    """Every region on one part, checked against each other and the geometry."""

    part: FlashSpec
    regions: tuple[FlashRegion, ...]

    def __post_init__(self) -> None:
        if not self.regions:
            raise ConfigurationError("a layout has regions in it")
        names = [region.name for region in self.regions]
        if len(set(names)) != len(names):
            raise ConfigurationError("two regions share a name", names=names)
        for region in self.regions:
            if region.end > self.part.capacity_bytes:
                raise ConfigurationError(
                    "a region runs off the end of the part",
                    region=region.name,
                    part=self.part.name,
                )
        ordered = sorted(self.regions, key=lambda region: region.start)
        for first, second in zip(ordered, ordered[1:], strict=False):
            if first.overlaps(second):
                raise ConfigurationError(
                    "two regions overlap",
                    first=first.name,
                    second=second.name,
                )
            if self.part.sector_of(first.end - 1) == self.part.sector_of(second.start):
                raise ConfigurationError(
                    "two regions share a sector, so one cannot be erased alone",
                    first=first.name,
                    second=second.name,
                )

    def region(self, name: str) -> FlashRegion:
        wanted = slug(name)
        for region in self.regions:
            if region.name == wanted:
                return region
        raise NotFound("flash region", wanted)

    def region_at(self, address: int) -> FlashRegion | None:
        for region in self.regions:
            if region.contains(address):
                return region
        return None

    @property
    def used(self) -> int:
        return sum(region.length for region in self.regions)

    @property
    def free(self) -> int:
        return self.part.capacity_bytes - self.used

    def sector_span(self, name: str) -> tuple[int, int]:
        """First and last sector of a region, which is what an erase needs."""
        region = self.region(name)
        return (self.part.sector_of(region.start), self.part.sector_of(region.end - 1))

    def sectors_for(self, name: str) -> int:
        first, last = self.sector_span(name)
        return last - first + 1

    def describe(self) -> str:
        lines = [region.describe() for region in sorted(self.regions, key=lambda r: r.start)]
        return f"{self.part.name}: " + ", ".join(lines)


def _round_up_to_sector(part: FlashSpec, size: int) -> int:
    sectors = -(-size // part.sector_bytes)
    return sectors * part.sector_bytes


def single_image_layout(part: FlashSpec, image_size: int) -> FlashLayout:
    """One image at address zero, rounded up to whole sectors."""
    if image_size <= 0:
        raise ValidationError("an image has some bytes", field="image_size")
    length = _round_up_to_sector(part, image_size)
    if length > part.capacity_bytes:
        raise ConfigurationError(
            "that image does not fit the part",
            part=part.name,
            wanted=image_size,
            capacity=part.capacity_bytes,
        )
    return FlashLayout(part=part, regions=(FlashRegion("golden", 0, length, "golden"),))


def multiboot_layout(
    part: FlashSpec,
    golden_size: int,
    update_size: int,
    *,
    update_start: int | None = None,
) -> FlashLayout:
    """A golden image at zero and an update image above it.

    The update address can be pinned, because the warm boot address is baked
    into the golden image and a layout that moves it silently is a board that
    boots the wrong thing. When it is not given, the update goes in the next
    sector after the golden image with one spare sector between them, so that
    growing the golden image slightly does not immediately need a new address.
    """
    if golden_size <= 0 or update_size <= 0:
        raise ValidationError("both images have some bytes", field="size")
    golden_length = _round_up_to_sector(part, golden_size)
    start = update_start
    if start is None:
        start = golden_length + part.sector_bytes
    if not part.is_sector_aligned(start):
        raise ConfigurationError(
            "the update image has to start on a sector boundary",
            start=start,
            sector=part.sector_bytes,
        )
    update_length = _round_up_to_sector(part, update_size)
    if start + update_length > part.capacity_bytes:
        raise ConfigurationError(
            "the pair does not fit the part",
            part=part.name,
            wanted=start + update_length,
            capacity=part.capacity_bytes,
        )
    return FlashLayout(
        part=part,
        regions=(
            FlashRegion("golden", 0, golden_length, "golden"),
            FlashRegion("update", start, update_length, "update"),
        ),
    )
