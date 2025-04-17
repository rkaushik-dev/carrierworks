"""One conversation with one chain, from reset to release.

The session owns two things the layers underneath deliberately do not: where
the controller currently is, and which chain it is talking to. Everything it
offers is spelled as an operation somebody in the lab would recognise, so the
code that programs a device reads as a list of scans rather than as a list of
TMS runs.

It always starts from reset. A session that inherits whatever state the last
one left behind works perfectly until the day it does not, and the failure
looks like a bad cable rather than like a bug.
"""

from __future__ import annotations

from types import TracebackType

from fpga.catalog.idcode import IdCode
from fpga.errors import ChainError, ValidationError
from fpga.jtag.bits import BitVector
from fpga.jtag.chain import ScanChain
from fpga.jtag.discover import IDCODE_BITS, discover_taps
from fpga.jtag.scan import dr_scan, ir_scan, take_dr_payload
from fpga.jtag.tap import RESET_CLOCKS, path_between
from fpga.jtag.transport import Transport
from fpga.jtag.verify import verify_chain
from fpga.measure import Frequency

__all__ = ["ScanSession"]


def _tms_vector(bits: tuple[int, ...] | list[int]) -> BitVector:
    value = 0
    for index, bit in enumerate(bits):
        value |= bit << index
    return BitVector(value, len(bits))


class ScanSession:
    """A live conversation with a chain."""

    def __init__(
        self,
        transport: Transport,
        chain: ScanChain,
        *,
        clock: Frequency | None = None,
    ) -> None:
        self.transport = transport
        self.chain = chain
        self.state = "test_logic_reset"
        self.clocks = 0
        if clock is not None:
            transport.set_clock(clock)
        self.reset()

    def __enter__(self) -> ScanSession:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        self.close()

    def close(self) -> None:
        self.transport.close()

    def _pulse(self, tms: BitVector, tdi: BitVector) -> BitVector:
        self.clocks += len(tms)
        return self.transport.pulse(tms, tdi)

    def reset(self) -> None:
        """Five clocks with TMS high, which lands in reset from anywhere."""
        self._pulse(BitVector.ones(RESET_CLOCKS), BitVector.zeros(RESET_CLOCKS))
        self.state = "test_logic_reset"

    def move_to(self, state: str) -> None:
        path = path_between(self.state, state)
        if path:
            self._pulse(_tms_vector(path), BitVector.zeros(len(path)))
        self.state = state

    def run_test_idle(self, clocks: int) -> None:
        """Sit in idle for a while, which is how a device is given time."""
        if clocks < 0:
            raise ValidationError("cannot idle for negative clocks", field="clocks")
        self.move_to("run_test_idle")
        if clocks:
            self._pulse(BitVector.zeros(clocks), BitVector.zeros(clocks))

    def _shift(self, state: str, payload: BitVector) -> BitVector:
        if not len(payload):
            raise ValidationError("nothing to shift", field="payload")
        self.move_to(state)
        length = len(payload)
        tms = _tms_vector([0] * (length - 1) + [1])
        captured = self._pulse(tms, payload)
        self.state = "exit1_ir" if state == "shift_ir" else "exit1_dr"
        self.move_to("run_test_idle")
        return captured

    def shift_ir(self, instructions: dict[str, str] | None = None) -> BitVector:
        """Load an instruction into every device on the chain."""
        return self._shift("shift_ir", ir_scan(self.chain, instructions))

    def shift_dr(self, label: str, payload: BitVector) -> BitVector:
        """Shift a payload at one device and return what that device gave back."""
        whole = self._shift("shift_dr", dr_scan(self.chain, label, payload))
        return take_dr_payload(self.chain, label, whole, len(payload))

    def scan_idcodes(self, *, reset: bool = True) -> BitVector:
        """The raw discovery scan, ones in, whatever comes out.

        Reset first unless told not to. Skipping the reset is how a caller
        asks what the chain is doing right now rather than what it holds when
        it is left alone, and the bypass check in the reader depends on it.
        """
        if reset:
            self.reset()
        width = len(self.chain) * IDCODE_BITS + IDCODE_BITS
        return self._shift("shift_dr", BitVector.ones(width))

    def read_idcodes(self, *, reset: bool = True) -> tuple[IdCode, ...]:
        """The idcodes on the chain, in the listed order rather than shift order."""
        found = discover_taps(self.scan_idcodes(reset=reset))
        codes = []
        for tap in reversed(found):
            if tap.idcode is None:
                raise ChainError("a device on the chain answered in bypass")
            codes.append(tap.idcode)
        return tuple(codes)

    def verify(self) -> ScanChain:
        """Check the chain against the one on file and return what answered."""
        return verify_chain(self.chain, self.scan_idcodes())

    def load_instruction(self, label: str, instruction: str) -> None:
        """One device gets an instruction and everything else bypasses."""
        self.chain.index_of(label)
        self.shift_ir({label: instruction})
