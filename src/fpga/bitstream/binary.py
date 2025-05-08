"""Turning an image into the bytes a flash part or a driver wants.

Three conversions live here and each one exists because a different piece of
hardware counts bits in a different direction.

A .bin file is the configuration data with the header thrown away, which is
what a driver shifts straight into the device. A flash image is that with the
bits inside each byte reversed, because the device reads SPI flash most
significant bit first while the flash returns bytes least significant bit
first, and the tools compensate at write time rather than at read time. A word
swapped image is for the parallel interfaces that latch sixteen or thirty two
bits at a time.

Every one of these is its own inverse, which is the only reason a mistake here
is survivable: apply it twice and you are back where you started. That is also
why the tests check the round trip rather than the byte values, except in the
one place where a fixed pattern proves the direction.
"""

from __future__ import annotations

from fpga.bitstream.image import Bitstream
from fpga.errors import ValidationError

__all__ = [
    "ERASED",
    "byte_swap",
    "pad_to",
    "reverse_bits",
    "split_pages",
    "to_bin",
    "to_flash_image",
    "trim_erased",
    "word_swap",
]

# An erased flash cell reads as ones, so padding is 0xff and never 0x00.
ERASED = 0xFF

# Bit reversal for every byte value, built once because a flash image is
# several megabytes and doing this a bit at a time is measurably slow.
_REVERSED = bytes(int(f"{value:08b}"[::-1], 2) for value in range(256))


def reverse_bits(data: bytes) -> bytes:
    """Reverse the bits inside each byte, leaving the byte order alone."""
    return data.translate(_REVERSED)


def byte_swap(data: bytes, width: int = 2) -> bytes:
    """Reverse the byte order inside each group of ``width`` bytes."""
    if width < 2:
        raise ValidationError("a swap needs at least two bytes", field="width", given=width)
    if len(data) % width:
        raise ValidationError(
            "the image is not a whole number of words",
            field="data",
            width=width,
            length=len(data),
        )
    out = bytearray(len(data))
    for offset in range(0, len(data), width):
        out[offset : offset + width] = data[offset : offset + width][::-1]
    return bytes(out)


def word_swap(data: bytes) -> bytes:
    """The thirty two bit case, which is what the parallel loaders want."""
    return byte_swap(data, 4)


def pad_to(data: bytes, length: int, fill: int = ERASED) -> bytes:
    """Pad an image out to a boundary with the value an erased part reads as."""
    if length < 0:
        raise ValidationError("cannot pad to a negative length", field="length", given=length)
    if not 0 <= fill <= 0xFF:
        raise ValidationError("the fill is one byte", field="fill", given=fill)
    if len(data) > length:
        raise ValidationError(
            "the image is already longer than that",
            field="length",
            wanted=length,
            have=len(data),
        )
    return data + bytes([fill]) * (length - len(data))


def trim_erased(data: bytes, fill: int = ERASED) -> bytes:
    """Drop the erased padding off the end, which is how a readback is compared."""
    end = len(data)
    while end and data[end - 1] == fill:
        end -= 1
    return data[:end]


def split_pages(data: bytes, page: int) -> tuple[bytes, ...]:
    """Cut an image into page sized writes, the last one possibly short."""
    if page <= 0:
        raise ValidationError("a page has a size", field="page", given=page)
    return tuple(data[offset : offset + page] for offset in range(0, len(data), page))


def to_bin(image: Bitstream) -> bytes:
    """The configuration data with the header removed."""
    return image.header.data


def to_flash_image(image: Bitstream, *, swap: bool = True) -> bytes:
    """The bytes to lay into SPI flash.

    Swapping is the normal case and the reason is above. It is switchable
    because a couple of the older carriers in the lab have the swap done in
    their own boot code, and programming a swapped image into one of those
    produces a device that never comes out of reset with no other symptom.
    """
    data = to_bin(image)
    return reverse_bits(data) if swap else data
