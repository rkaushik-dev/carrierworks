"""Bit vectors that keep their own length and their own order.

A scan chain shifts least significant bit first, a person writes bit strings
most significant on the left, and a byte buffer for a cable driver packs eight
bits at a time from the low end. Three orders for the same thing, and every one
of them is correct in its own context. What is not correct is losing the
length: a vector of six zeros and a vector of eight zeros are different scans
and an integer cannot tell them apart.

So the length is carried, the value is an integer, and every conversion says
which end it starts from in its name.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass

from fpga.errors import ValidationError

__all__ = ["BitVector"]


@dataclass(frozen=True, slots=True)
class BitVector:
    """A run of bits of a known length, held least significant bit first."""

    value: int
    length: int

    def __post_init__(self) -> None:
        if self.length < 0:
            raise ValidationError("a vector cannot be shorter than nothing", field="length")
        if self.value < 0:
            raise ValidationError("a vector is not negative", field="value")
        if self.length and self.value >> self.length:
            raise ValidationError(
                "value does not fit in that many bits",
                field="value",
                length=self.length,
            )
        if self.length == 0 and self.value:
            raise ValidationError("an empty vector holds nothing", field="value")

    @classmethod
    def zeros(cls, length: int) -> BitVector:
        return cls(0, length)

    @classmethod
    def ones(cls, length: int) -> BitVector:
        return cls((1 << length) - 1 if length else 0, length)

    @classmethod
    def parse(cls, text: str) -> BitVector:
        """From a written string, most significant bit on the left."""
        cleaned = text.strip().replace(" ", "").replace("_", "")
        if any(character not in "01" for character in cleaned):
            raise ValidationError("a bit string is ones and zeros", field="bits", given=text)
        if not cleaned:
            return cls(0, 0)
        return cls(int(cleaned, 2), len(cleaned))

    @classmethod
    def from_bits(cls, bits: Iterable[int]) -> BitVector:
        """From bits in clock order, the first one out first."""
        value = 0
        length = 0
        for bit in bits:
            if bit not in (0, 1):
                raise ValidationError("a vector is made of ones and zeros", field="bits")
            value |= bit << length
            length += 1
        return cls(value, length)

    @classmethod
    def join(cls, parts: Iterable[BitVector]) -> BitVector:
        """Lay vectors end to end in shift order, first argument out first."""
        value = 0
        length = 0
        for part in parts:
            value |= part.value << length
            length += part.length
        return cls(value, length)

    @classmethod
    def from_bytes(cls, data: bytes, length: int | None = None) -> BitVector:
        """From a driver buffer, first byte lowest, bit zero of it first."""
        width = len(data) * 8 if length is None else length
        if width > len(data) * 8:
            raise ValidationError("not enough bytes for that many bits", field="length")
        value = int.from_bytes(data, "little")
        if width < len(data) * 8:
            value &= (1 << width) - 1
        return cls(value, width)

    def to_bytes(self) -> bytes:
        """Out to a driver buffer, padded up to a whole number of bytes."""
        return self.value.to_bytes((self.length + 7) // 8, "little")

    def bit(self, index: int) -> int:
        """One bit, counted from the end that shifts first."""
        if not 0 <= index < self.length:
            raise ValidationError("bit index is outside the vector", field="index", given=index)
        return (self.value >> index) & 1

    def slice(self, start: int, length: int) -> BitVector:
        """A run of bits, counted from the shifting end."""
        if start < 0 or length < 0 or start + length > self.length:
            raise ValidationError(
                "slice is outside the vector",
                field="slice",
                start=start,
                length=length,
                have=self.length,
            )
        if length == 0:
            return BitVector(0, 0)
        return BitVector((self.value >> start) & ((1 << length) - 1), length)

    def bits(self) -> tuple[int, ...]:
        """Every bit in clock order, which is what a driver loop wants."""
        return tuple((self.value >> index) & 1 for index in range(self.length))

    def split(self, widths: Iterable[int]) -> tuple[BitVector, ...]:
        """Cut into consecutive runs from the shifting end.

        The widths have to add up to the whole vector. A chain register that
        does not add up is a bug in whoever built it, not something to pad.
        """
        pieces = []
        offset = 0
        for width in widths:
            pieces.append(self.slice(offset, width))
            offset += width
        if offset != self.length:
            raise ValidationError(
                "widths do not add up to the vector",
                field="widths",
                covered=offset,
                length=self.length,
            )
        return tuple(pieces)

    def reversed(self) -> BitVector:
        """The same bits, other way round, for the registers that want it."""
        value = 0
        for index in range(self.length):
            value = (value << 1) | ((self.value >> index) & 1)
        return BitVector(value, self.length)

    def chunks(self, size: int) -> tuple[BitVector, ...]:
        """Split into fixed size pieces from the shifting end, last one short."""
        if size <= 0:
            raise ValidationError("chunk size is positive", field="size", given=size)
        pieces = []
        offset = 0
        while offset < self.length:
            width = min(size, self.length - offset)
            pieces.append(self.slice(offset, width))
            offset += width
        return tuple(pieces)

    def __add__(self, other: BitVector) -> BitVector:
        """Concatenation in shift order: self goes out first, other after it."""
        if not isinstance(other, BitVector):  # pragma: no cover - guarded by typing
            return NotImplemented
        return BitVector(self.value | (other.value << self.length), self.length + other.length)

    def __len__(self) -> int:
        return self.length

    def __str__(self) -> str:
        """Written out most significant bit first, the way people read them."""
        if not self.length:
            return ""
        return format(self.value, f"0{self.length}b")

    def __repr__(self) -> str:
        return f"BitVector('{self}')"
