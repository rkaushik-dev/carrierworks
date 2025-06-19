"""The jump that makes a golden image a fallback rather than a dead end.

Multiboot works by putting a very short sequence at the very start of the flash
that tells the device to go and load a different address instead. Sync, a write
to the warm boot start address register, then the internal reprogram command.
The device reads that, sets its address, restarts configuration from there, and
if the image it finds is corrupt it falls back to address zero, where the
golden image sits.

The sequence is eight words long and every one of them matters. The dummy word
lets the device find the clock, the sync word starts the packet stream, the
noop after the command gives the internal restart somewhere to land. Writing it
by hand from the user guide is how the lab did it before this module, and the
one time a noop was left out the board came up on the golden image with no
indication that anything had been skipped.

The address written into the register is a byte address in the flash, so it is
the same number the layout uses for the region.
"""

from __future__ import annotations

from fpga.bitstream.binary import ERASED, pad_to
from fpga.bitstream.packets import SYNC_WORD, type1, words_to_bytes
from fpga.errors import ValidationError
from fpga.flash.layout import FlashLayout

__all__ = [
    "MULTIBOOT_WORDS",
    "assemble_multiboot",
    "read_warm_boot_address",
    "golden_with_jump",
    "warm_boot_prefix",
]

_DUMMY = 0xFFFFFFFF
_NOOP = type1(opcode=0, register=0, word_count=0)
_WBSTAR = 16
_CMD = 4
_IPROG = 15
MULTIBOOT_WORDS = 8

# The warm boot address is twenty nine bits; the two above it are the read
# settings for the flash and are left at zero for every part the lab uses.
_ADDRESS_MASK = 0x1FFFFFFF


def warm_boot_prefix(address: int) -> bytes:
    """The eight word sequence that sends the device to another address."""
    if not 0 <= address <= _ADDRESS_MASK:
        raise ValidationError(
            "a warm boot address is twenty nine bits",
            field="address",
            given=address,
        )
    words = (
        _DUMMY,
        SYNC_WORD,
        _NOOP,
        type1(opcode=2, register=_WBSTAR, word_count=1),
        address,
        type1(opcode=2, register=_CMD, word_count=1),
        _IPROG,
        _NOOP,
    )
    return words_to_bytes(words)


def read_warm_boot_address(data: bytes) -> int | None:
    """The address a prefix jumps to, or nothing if this is not one.

    Deliberately strict. Anything that is not exactly the sequence this module
    writes is treated as an ordinary image, because a partial match is far
    more likely to be a bitstream that happens to start with a sync word than
    a multiboot header somebody hand assembled.
    """
    if len(data) < MULTIBOOT_WORDS * 4:
        return None
    words = [int.from_bytes(data[index * 4 : index * 4 + 4], "big") for index in range(8)]
    if words[0] != _DUMMY or words[1] != SYNC_WORD or words[2] != _NOOP:
        return None
    if words[3] != type1(opcode=2, register=_WBSTAR, word_count=1):
        return None
    if words[5] != type1(opcode=2, register=_CMD, word_count=1) or words[6] != _IPROG:
        return None
    return words[4] & _ADDRESS_MASK


def assemble_multiboot(
    layout: FlashLayout,
    golden: bytes,
    update: bytes,
    *,
    fill: int = ERASED,
) -> bytes:
    """The whole flash image: jump, golden, gap, update.

    The prefix goes in front of the golden image rather than in a region of
    its own, because the device reads it as the first words of whatever is at
    address zero and the golden image has to follow it immediately.
    """
    golden_region = layout.region("golden")
    update_region = layout.region("update")
    if golden_region.start != 0:
        raise ValidationError(
            "the golden image has to sit at address zero",
            field="layout",
            start=golden_region.start,
        )
    prefix = warm_boot_prefix(update_region.start)
    head = prefix + golden
    if not golden_region.holds(len(head)):
        raise ValidationError(
            "the golden image and its jump do not fit their region",
            field="golden",
            wanted=len(head),
            room=golden_region.length,
        )
    if not update_region.holds(len(update)):
        raise ValidationError(
            "the update image does not fit its region",
            field="update",
            wanted=len(update),
            room=update_region.length,
        )
    out = pad_to(head, update_region.start, fill)
    return out + update


def golden_with_jump(layout: FlashLayout, golden: bytes) -> bytes:
    """The golden image with its jump in front, for programming on its own."""
    update_region = layout.region("update")
    return warm_boot_prefix(update_region.start) + golden
