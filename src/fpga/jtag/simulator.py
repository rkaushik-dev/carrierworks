"""A chain that behaves like silicon, without any silicon.

This is not a toy. Every piece of the bench above the transport is written
against a real chain and there is no way to test any of it honestly without
something that shifts bits the way devices do, including the parts that are
easy to get wrong: the capture of 01 into the instruction register, the single
bit a bypassed device contributes, and the fact that the last data bit goes out
on the same clock that leaves the shift state.

The whole chain is one shift register. Bits go into the device nearest TDI and
come out of the device nearest TDO, so the simulator holds the concatenated
register with the TDO end at index zero and pushes new bits on at the other.
That is the entire trick, and everything else is bookkeeping around it.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from fpga.catalog.devices import device_for
from fpga.errors import ValidationError
from fpga.jtag.bits import BitVector
from fpga.jtag.chain import ScanChain
from fpga.jtag.scan import INSTRUCTIONS
from fpga.jtag.tap import step
from fpga.measure import Frequency

__all__ = [
    "ChainSimulator",
    "SimulatedDevice",
]

_IDCODE = INSTRUCTIONS["idcode"]
_USERCODE = INSTRUCTIONS["usercode"]
_BYPASS_BITS = 1
_IDCODE_BITS = 32
_CAPTURED_IR = 0b01


@dataclass(slots=True)
class SimulatedDevice:
    """One device on the simulated chain."""

    label: str
    idcode: int
    ir_length: int
    usercode: int = 0xFFFFFFFF
    user_register_bits: int = 32
    instruction: int = _IDCODE
    last_dr_write: BitVector | None = None
    register: list[int] = field(default_factory=list)

    @classmethod
    def for_device(cls, label: str, device: str) -> SimulatedDevice:
        spec = device_for(device)
        return cls(label=label, idcode=spec.idcode, ir_length=spec.ir_length)

    def reset(self) -> None:
        self.instruction = _IDCODE
        self.last_dr_write = None

    @property
    def in_bypass(self) -> bool:
        return self.instruction == INSTRUCTIONS["bypass"]

    def dr_length(self) -> int:
        if self.instruction in (_IDCODE, _USERCODE):
            return _IDCODE_BITS
        if self.instruction in (
            INSTRUCTIONS["user1"],
            INSTRUCTIONS["user2"],
            INSTRUCTIONS["user3"],
            INSTRUCTIONS["user4"],
        ):
            return self.user_register_bits
        return _BYPASS_BITS

    def dr_capture(self) -> BitVector:
        """What this device loads into the data register on capture."""
        if self.instruction == _IDCODE:
            return BitVector(self.idcode, _IDCODE_BITS)
        if self.instruction == _USERCODE:
            return BitVector(self.usercode, _IDCODE_BITS)
        return BitVector.zeros(self.dr_length())

    def ir_capture(self) -> BitVector:
        return BitVector(_CAPTURED_IR, self.ir_length)


class ChainSimulator:
    """A transport that answers the way a real chain would."""

    def __init__(self, devices: list[SimulatedDevice]) -> None:
        if not devices:
            raise ValidationError("a simulated chain needs a device", field="devices")
        self.devices = devices
        self.state = "test_logic_reset"
        self.clock = Frequency.megahertz(1)
        self.closed = False
        self._register: list[int] = []
        self._shifting = ""
        self.reset()

    @classmethod
    def of_devices(cls, *devices: str) -> ChainSimulator:
        return cls(
            [
                SimulatedDevice.for_device(f"u{number}", device)
                for number, device in enumerate(devices, start=1)
            ]
        )

    @classmethod
    def for_chain(cls, chain: ScanChain) -> ChainSimulator:
        built = []
        for tap in chain.taps:
            if tap.device is None:
                raise ValidationError(
                    "a simulated chain cannot hold an unknown device",
                    field="chain",
                    tap=tap.label,
                )
            built.append(SimulatedDevice.for_device(tap.label, tap.device))
        return cls(built)

    @property
    def name(self) -> str:
        return "simulator"

    def set_clock(self, clock: Frequency) -> None:
        self.clock = clock

    def close(self) -> None:
        self.closed = True

    def reset(self) -> None:
        for device in self.devices:
            device.reset()
        self.state = "test_logic_reset"
        self._register = []
        self._shifting = ""

    def device(self, label: str) -> SimulatedDevice:
        for device in self.devices:
            if device.label == label:
                return device
        raise ValidationError("no such device on the simulated chain", field="label", given=label)

    def pulse(self, tms: BitVector, tdi: BitVector) -> BitVector:
        if self.closed:
            raise ValidationError("transport is closed", field="transport")
        if len(tms) != len(tdi):
            raise ValidationError("tms and tdi have to be the same length", field="pulse")
        if not len(tms):
            raise ValidationError("a pulse clocks at least one bit", field="pulse")
        out = 0
        for index in range(len(tms)):
            bit = self._clock(tms.bit(index), tdi.bit(index))
            out |= bit << index
        return BitVector(out, len(tms))

    def _clock(self, tms: int, tdi: int) -> int:
        out = 0
        if self._shifting:
            out = self._register[0] if self._register else 0
            self._register = [*self._register[1:], tdi]
        nxt = step(self.state, tms)
        if nxt == "test_logic_reset":
            self.reset()
            return out
        if nxt == "capture_dr":
            self._load_dr()
        elif nxt == "capture_ir":
            self._load_ir()
        elif nxt == "update_dr":
            self._apply_dr()
        elif nxt == "update_ir":
            self._apply_ir()
        self._shifting = "ir" if nxt == "shift_ir" else "dr" if nxt == "shift_dr" else ""
        self.state = nxt
        return out

    def _tdo_order(self) -> list[SimulatedDevice]:
        return list(reversed(self.devices))

    def _load_dr(self) -> None:
        bits: list[int] = []
        for device in self._tdo_order():
            captured = device.dr_capture()
            bits.extend(captured.bit(index) for index in range(len(captured)))
        self._register = bits

    def _load_ir(self) -> None:
        bits: list[int] = []
        for device in self._tdo_order():
            captured = device.ir_capture()
            bits.extend(captured.bit(index) for index in range(len(captured)))
        self._register = bits

    def _apply_ir(self) -> None:
        offset = 0
        for device in self._tdo_order():
            width = device.ir_length
            chunk = self._register[offset : offset + width]
            if len(chunk) == width:
                device.instruction = sum(bit << position for position, bit in enumerate(chunk))
            offset += width

    def _apply_dr(self) -> None:
        offset = 0
        for device in self._tdo_order():
            width = device.dr_length()
            chunk = self._register[offset : offset + width]
            if len(chunk) == width:
                value = sum(bit << position for position, bit in enumerate(chunk))
                device.last_dr_write = BitVector(value, width)
            offset += width
