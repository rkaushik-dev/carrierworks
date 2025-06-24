"""The configuration flash parts the boards carry, and their geometry.

Every number here is a constraint on something the bench does later. A page is
the largest write the part accepts in one go and writes that cross a page
boundary wrap round to the start of the same page instead of carrying on, which
is a silent corruption rather than an error. A subsector is the smallest thing
that can be erased, so an image that starts halfway through one cannot be
updated without destroying whatever shares it. The address width decides
whether the part needs the four byte addressing command at all, and a part in
three byte mode simply cannot see anything past sixteen megabytes.

The erase and program times are the datasheet typicals. They are here so a job
can say how long it expects to take before it starts, because an operator
watching a bar that has not moved for four minutes will pull the cable.
"""

from __future__ import annotations

from dataclasses import dataclass

from fpga.catalog.index import CatalogIndex
from fpga.errors import CatalogError, ValidationError

__all__ = [
    "FLASH_PARTS",
    "FlashSpec",
    "all_flash_parts",
    "flash_for_jedec_id",
    "flash_part",
    "lookup_flash",
]


@dataclass(frozen=True, slots=True)
class FlashSpec:
    """One SPI configuration flash device."""

    name: str
    vendor: str
    jedec_id: int
    capacity_bytes: int
    page_bytes: int
    subsector_bytes: int
    sector_bytes: int
    page_program_us: int
    subsector_erase_ms: int
    sector_erase_ms: int
    bulk_erase_s: int
    quad_capable: bool = True
    erase_cycles: int = 100_000

    def __post_init__(self) -> None:
        if self.capacity_bytes % self.sector_bytes:
            raise CatalogError("capacity is not a whole number of sectors", part=self.name)
        if self.sector_bytes % self.subsector_bytes:
            raise CatalogError("sector is not a whole number of subsectors", part=self.name)
        if self.subsector_bytes % self.page_bytes:
            raise CatalogError("subsector is not a whole number of pages", part=self.name)

    @property
    def address_bytes(self) -> int:
        """Three byte addressing tops out at sixteen megabytes."""
        return 3 if self.capacity_bytes <= 16 * 1024 * 1024 else 4

    @property
    def sector_count(self) -> int:
        return self.capacity_bytes // self.sector_bytes

    @property
    def subsectors_per_sector(self) -> int:
        return self.sector_bytes // self.subsector_bytes

    @property
    def manufacturer_id(self) -> int:
        return (self.jedec_id >> 16) & 0xFF

    def holds(self, size: int) -> bool:
        return 0 <= size <= self.capacity_bytes

    def check_address(self, address: int) -> None:
        if not 0 <= address < self.capacity_bytes:
            raise ValidationError(
                "address is outside the part",
                field="address",
                given=address,
                part=self.name,
            )

    def sector_of(self, address: int) -> int:
        self.check_address(address)
        return address // self.sector_bytes

    def sector_base(self, address: int) -> int:
        return self.sector_of(address) * self.sector_bytes

    def subsector_of(self, address: int) -> int:
        self.check_address(address)
        return address // self.subsector_bytes

    def is_sector_aligned(self, address: int) -> bool:
        return address % self.sector_bytes == 0

    def is_subsector_aligned(self, address: int) -> bool:
        return address % self.subsector_bytes == 0

    def pages_spanned(self, address: int, length: int) -> int:
        """How many page writes a run of bytes turns into.

        The first page is usually partial, which is why this is not a division.
        """
        if length < 0:
            raise ValidationError("length must not be negative", field="length", given=length)
        if length == 0:
            return 0
        self.check_address(address)
        first = address // self.page_bytes
        last = (address + length - 1) // self.page_bytes
        return last - first + 1


_CATALOG: tuple[FlashSpec, ...] = (
    FlashSpec(
        name="n25q128a13",
        vendor="micron",
        jedec_id=0x20BA18,
        capacity_bytes=16 * 1024 * 1024,
        page_bytes=256,
        subsector_bytes=4096,
        sector_bytes=65536,
        page_program_us=500,
        subsector_erase_ms=300,
        sector_erase_ms=700,
        bulk_erase_s=250,
    ),
    FlashSpec(
        name="n25q256a11",
        vendor="micron",
        jedec_id=0x20BB19,
        capacity_bytes=32 * 1024 * 1024,
        page_bytes=256,
        subsector_bytes=4096,
        sector_bytes=65536,
        page_program_us=500,
        subsector_erase_ms=300,
        sector_erase_ms=700,
        bulk_erase_s=480,
    ),
    FlashSpec(
        name="mt25ql512",
        vendor="micron",
        jedec_id=0x20BA20,
        capacity_bytes=64 * 1024 * 1024,
        page_bytes=256,
        subsector_bytes=4096,
        sector_bytes=65536,
        page_program_us=400,
        subsector_erase_ms=400,
        sector_erase_ms=1000,
        bulk_erase_s=600,
    ),
    FlashSpec(
        name="mx25l25635f",
        vendor="macronix",
        jedec_id=0xC22019,
        capacity_bytes=32 * 1024 * 1024,
        page_bytes=256,
        subsector_bytes=4096,
        sector_bytes=65536,
        page_program_us=750,
        subsector_erase_ms=250,
        sector_erase_ms=900,
        bulk_erase_s=300,
    ),
    FlashSpec(
        name="s25fl128s",
        vendor="spansion",
        jedec_id=0x012018,
        capacity_bytes=16 * 1024 * 1024,
        page_bytes=256,
        subsector_bytes=4096,
        sector_bytes=65536,
        page_program_us=600,
        subsector_erase_ms=290,
        sector_erase_ms=520,
        bulk_erase_s=165,
    ),
    FlashSpec(
        name="w25q64jv",
        vendor="winbond",
        jedec_id=0xEF4017,
        capacity_bytes=8 * 1024 * 1024,
        page_bytes=256,
        subsector_bytes=4096,
        sector_bytes=65536,
        page_program_us=700,
        subsector_erase_ms=120,
        sector_erase_ms=1000,
        bulk_erase_s=100,
        erase_cycles=100_000,
    ),
)

FLASH_PARTS: CatalogIndex[FlashSpec] = CatalogIndex(
    "flash part",
    _CATALOG,
    key=lambda spec: spec.name,
    secondary={"jedec id": lambda spec: spec.jedec_id},
)


def lookup_flash(name: str) -> FlashSpec | None:
    return FLASH_PARTS.lookup(name)


def flash_part(name: str) -> FlashSpec:
    return FLASH_PARTS.require(name)


def flash_for_jedec_id(jedec_id: int) -> FlashSpec:
    """Whatever answered the read id command."""
    return FLASH_PARTS.require_by("jedec id", jedec_id, shown=f"0x{jedec_id:06x}")


def all_flash_parts() -> tuple[FlashSpec, ...]:
    return FLASH_PARTS.all()
