"""The 32 bits every device on a chain shifts out when you ask who it is.

The layout is fixed by IEEE 1149.1 and it is worth spelling out because three
different bugs hide in it. Bit zero is always one, so a device that reads back
with bit zero clear is a wiring fault rather than an unknown part. The eleven
bits above that are the manufacturer, in the JEDEC encoding where the count of
continuation bytes goes in the top four, which is why ARM comes out as 0x23b
and not as something that looks like a number anyone chose. The version nibble
at the top changes with the silicon stepping, so nothing may compare on it.

All ones is the other special case. An open chain reads as all ones because the
line floats high, so 0xffffffff is never a device and always a broken cable.
"""

from __future__ import annotations

from dataclasses import dataclass

from fpga.errors import ValidationError

__all__ = [
    "ALL_ONES",
    "MANUFACTURERS",
    "IdCode",
    "manufacturer_name",
]

ALL_ONES = 0xFFFFFFFF

# JEDEC manufacturer identifiers, in the form the idcode carries them.
MANUFACTURERS: dict[int, str] = {
    0x049: "xilinx",
    0x043: "lattice",
    0x06E: "altera",
    0x0CF: "microsemi",
    0x23B: "arm",
    0x034: "cypress",
    0x015: "micron",
}


def manufacturer_name(code: int) -> str:
    """A JEDEC id as a name, or the raw value when the lab has not met it."""
    return MANUFACTURERS.get(code, f"unknown-0x{code:03x}")


@dataclass(frozen=True, slots=True)
class IdCode:
    """One idcode word, held as the four fields it actually is."""

    value: int

    def __post_init__(self) -> None:
        if not 0 <= self.value <= ALL_ONES:
            raise ValidationError("idcode does not fit in 32 bits", field="idcode")

    @classmethod
    def parse(cls, text: str | int) -> IdCode:
        """Read an idcode from a scan log, a config file or a person."""
        if isinstance(text, int):
            return cls(text)
        cleaned = text.strip().lower().replace("_", "").replace(" ", "")
        if not cleaned:
            raise ValidationError("empty idcode", field="idcode")
        try:
            value = int(cleaned, 16)
        except ValueError as exc:
            raise ValidationError("cannot read idcode " + repr(text), field="idcode") from exc
        return cls(value)

    @classmethod
    def build(cls, *, manufacturer: int, part: int, version: int = 0) -> IdCode:
        """Assemble an idcode from its fields, for tests and for fakes."""
        if not 0 <= manufacturer <= 0x7FF:
            raise ValidationError("manufacturer is eleven bits", field="manufacturer")
        if not 0 <= part <= 0xFFFF:
            raise ValidationError("part number is sixteen bits", field="part")
        if not 0 <= version <= 0xF:
            raise ValidationError("version is four bits", field="version")
        return cls((version << 28) | (part << 12) | (manufacturer << 1) | 1)

    @property
    def version(self) -> int:
        return (self.value >> 28) & 0xF

    @property
    def part(self) -> int:
        return (self.value >> 12) & 0xFFFF

    @property
    def manufacturer(self) -> int:
        return (self.value >> 1) & 0x7FF

    @property
    def manufacturer_name(self) -> str:
        return manufacturer_name(self.manufacturer)

    @property
    def has_valid_lsb(self) -> bool:
        return bool(self.value & 1)

    @property
    def is_floating(self) -> bool:
        """All ones is an open chain, not a part."""
        return self.value == ALL_ONES

    @property
    def is_plausible(self) -> bool:
        """Whether this could be a real device rather than a wiring fault.

        A manufacturer field of all ones is the same floating line seen one
        field at a time, and zero is what a chain held low reads as.
        """
        if self.is_floating or self.value == 0 or not self.has_valid_lsb:
            return False
        return self.manufacturer not in (0x000, 0x7FF)

    def without_version(self) -> IdCode:
        """The same idcode with the stepping nibble cleared, for comparison."""
        return IdCode(self.value & 0x0FFFFFFF)

    def same_device(self, other: IdCode) -> bool:
        return self.without_version().value == other.without_version().value

    def __str__(self) -> str:
        return f"0x{self.value:08x}"
