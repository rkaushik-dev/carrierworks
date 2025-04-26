"""Building the vectors that actually go out on the wire.

Two rules cover everything in this module. A device that is not the target of a
scan gets the bypass instruction, which is all ones whatever the width, and in
a data register scan it contributes exactly one bit. And the first bit shifted
in travels furthest, so it belongs to the device nearest TDO, which is the last
one in the listed order.

Get the second rule backwards and everything still runs. The scans are the
right length, the driver is happy, and the answers are for the wrong device.
That is why the fixtures in the tests spell out bit patterns by hand rather
than round tripping through these same functions.
"""

from __future__ import annotations

from fpga.errors import NotFound, ValidationError
from fpga.jtag.bits import BitVector
from fpga.jtag.chain import ScanChain

__all__ = [
    "INSTRUCTIONS",
    "bypass_instruction",
    "dr_capture_offset",
    "dr_scan",
    "instruction_code",
    "ir_scan",
    "take_dr_payload",
]

# Seven series instruction opcodes, six bits wide.
INSTRUCTIONS: dict[str, int] = {
    "extest": 0b100110,
    "sample": 0b000001,
    "preload": 0b000001,
    "idcode": 0b001001,
    "usercode": 0b001000,
    "user1": 0b000010,
    "user2": 0b000011,
    "user3": 0b100010,
    "user4": 0b100011,
    "cfg_out": 0b000100,
    "cfg_in": 0b000101,
    "jprogram": 0b001011,
    "jstart": 0b001100,
    "jshutdown": 0b001101,
    "bypass": 0b111111,
}


def bypass_instruction(ir_length: int) -> BitVector:
    """All ones, which is bypass on every device that follows the standard."""
    return BitVector.ones(ir_length)


def instruction_code(name: str, ir_length: int) -> BitVector:
    """One named instruction, as bits of the right width for its device."""
    key = name.strip().lower()
    if key not in INSTRUCTIONS:
        raise NotFound("instruction", key)
    code = INSTRUCTIONS[key]
    if key == "bypass":
        return bypass_instruction(ir_length)
    if code >> ir_length:
        raise ValidationError(
            "instruction does not fit that device",
            field="instruction",
            given=name,
            ir_length=ir_length,
        )
    return BitVector(code, ir_length)


def ir_scan(chain: ScanChain, instructions: dict[str, str] | None = None) -> BitVector:
    """The whole instruction register scan for a chain.

    Anything not named gets bypass. The vector is in shift order, so the bits
    for the device nearest TDO come first.
    """
    wanted = {key.strip().lower(): value for key, value in (instructions or {}).items()}
    unknown = set(wanted) - set(chain.labels())
    if unknown:
        raise NotFound("tap", ", ".join(sorted(unknown)))
    return BitVector.join(
        instruction_code(wanted.get(tap.label, "bypass"), tap.ir_length)
        for tap in chain.in_tdo_order()
    )


def dr_capture_offset(chain: ScanChain, label: str) -> int:
    """How many bypass bits arrive before the device's own data."""
    return chain.devices_before_tdo(chain.index_of(label))


def dr_scan(chain: ScanChain, label: str, payload: BitVector) -> BitVector:
    """A data register scan carrying a payload for one device.

    Every other device is in bypass and contributes a single bit, so the scan
    is the payload with one bit per device on either side of it.
    """
    index = chain.index_of(label)
    return BitVector.join(
        (
            BitVector.zeros(chain.devices_before_tdo(index)),
            payload,
            BitVector.zeros(chain.devices_after_tdo(index)),
        )
    )


def take_dr_payload(chain: ScanChain, label: str, captured: BitVector, length: int) -> BitVector:
    """Pull one device's answer out of a whole chain capture."""
    index = chain.index_of(label)
    offset = chain.devices_before_tdo(index)
    expected = offset + length + chain.devices_after_tdo(index)
    if len(captured) != expected:
        raise ValidationError(
            "capture is not the length this chain would produce",
            field="captured",
            expected=expected,
            given=len(captured),
        )
    return captured.slice(offset, length)
