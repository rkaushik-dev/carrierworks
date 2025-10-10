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
_CFG_IN = INSTRUCTIONS["cfg_in"]
_CFG_OUT = INSTRUCTIONS["cfg_out"]
_JPROGRAM = INSTRUCTIONS["jprogram"]
_JSTART = INSTRUCTIONS["jstart"]

# Bit fourteen of the status register is done, on every seven series part.
DONE_BIT = 1 << 14
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
    config_data: bytearray = field(default_factory=bytearray)
    configured: bool = False

    @classmethod
    def for_device(cls, label: str, device: str) -> SimulatedDevice:
        spec = device_for(device)
        return cls(label=label, idcode=spec.idcode, ir_length=spec.ir_length)

    def reset(self) -> None:
        """A tap reset puts the instruction back but leaves the device configured.

        That is what the silicon does, and it matters: a chain rescan in the
        middle of a session does not wipe the image somebody just loaded.
        """
        self.instruction = _IDCODE
        self.last_dr_write = None

    @property
    def in_bypass(self) -> bool:
        return self.instruction == INSTRUCTIONS["bypass"]

    def dr_length(self) -> int:
        if self.instruction in (_IDCODE, _USERCODE, _CFG_OUT):
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
        if self.instruction == _CFG_OUT:
            return BitVector(self.status_word(), _IDCODE_BITS)
        return BitVector.zeros(self.dr_length())

    def status_word(self) -> int:
        """The status register, with the done bit where the silicon puts it."""
        return DONE_BIT if self.configured else 0

    def apply_instruction(self, instruction: int) -> None:
        """Take the side effects an instruction has on configuration.

        Loading jprogram clears the device, jstart runs whatever has been
        shifted in, and everything else leaves the configuration alone.
        """
        if instruction == _JPROGRAM:
            self.config_data = bytearray()
            self.configured = False
        elif instruction == _JSTART and self.config_data:
            self.configured = True
        self.instruction = instruction

    def take_config(self, written: BitVector) -> None:
        self.config_data.extend(written.to_bytes())

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
        self._stream: list[int] = []
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
        """A simulator matching a described chain.

        A position that is not a catalogued device is still simulated as long
        as the chain says what it answers with, which is how the system
        controllers on the carriers get represented: the bench does not stock
        them as devices, but it knows their idcode and their register width.
        """
        built = []
        for tap in chain.taps:
            if tap.device is not None:
                built.append(SimulatedDevice.for_device(tap.label, tap.device))
            elif tap.expected_idcode is not None:
                built.append(
                    SimulatedDevice(
                        label=tap.label,
                        idcode=tap.expected_idcode,
                        ir_length=tap.ir_length,
                    )
                )
            else:
                raise ValidationError(
                    "a simulated chain cannot hold a position with no idcode",
                    field="chain",
                    tap=tap.label,
                )
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
        self._stream = []

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
        clocks = zip(tms.bits(), tdi.bits(), strict=True)
        return BitVector.from_bits(self._clock(tms_bit, tdi_bit) for tms_bit, tdi_bit in clocks)

    def _clock(self, tms: int, tdi: int) -> int:
        out = 0
        if self._shifting:
            out = self._register[0] if self._register else 0
            self._register = [*self._register[1:], tdi]
            if self._shifting == "dr":
                self._stream.append(tdi)
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
        self._stream = []
        self._register = list(
            BitVector.join(device.dr_capture() for device in self._tdo_order()).bits()
        )

    def _load_ir(self) -> None:
        self._register = list(
            BitVector.join(device.ir_capture() for device in self._tdo_order()).bits()
        )

    def _shifted(self, widths: list[int]) -> tuple[BitVector, ...] | None:
        """The chain register cut up per device, or nothing if it does not fit.

        A short register means the caller shifted fewer bits than the chain
        holds, which a real device would treat as a partial update. The
        simulator does the same thing a device does and ignores it.
        """
        register = BitVector.from_bits(self._register)
        if len(register) != sum(widths):
            return None
        return register.split(widths)

    def _apply_ir(self) -> None:
        order = self._tdo_order()
        pieces = self._shifted([device.ir_length for device in order])
        if pieces is None:
            return
        for device, piece in zip(order, pieces, strict=True):
            device.apply_instruction(piece.value)

    def _apply_dr(self) -> None:
        order = self._tdo_order()
        streaming = [device for device in order if device.instruction == _CFG_IN]
        if streaming:
            # A configuration write is a stream of any length rather than a
            # register of a known one, so it is taken from what was shifted in
            # rather than from what the chain register held.
            written = BitVector.from_bits(self._stream)
            for device in streaming:
                device.take_config(written)
                device.last_dr_write = written
            return
        pieces = self._shifted([device.dr_length() for device in order])
        if pieces is None:
            return
        for device, piece in zip(order, pieces, strict=True):
            device.last_dr_write = piece
