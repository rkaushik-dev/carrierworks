"""Loading an image into a device over JTAG.

The sequence is short and every step of it is in the configuration user guide,
which is worth saying because it looks like magic numbers otherwise.

JPROGRAM clears whatever was in the device and starts the internal
initialisation. It takes time, so the sequence idles afterwards rather than
racing on. CFG_IN opens the configuration port and the image is shifted in as
one long data register write, most significant bit of each byte first, which is
why the bytes go through the bit reverser on the way. JSTART runs the startup
sequence, which needs clocks rather than time. Then the status register is read
through CFG_OUT and the done bit says whether any of it worked.

Done not coming up is the interesting failure. It nearly always means the image
was for another device, and the bench has already checked that, so if it
happens here the next thing to suspect is the cable.
"""

from __future__ import annotations

from dataclasses import dataclass

from fpga.bitstream.binary import reverse_bits
from fpga.errors import BenchError, TransientBenchError
from fpga.jtag.bits import BitVector
from fpga.jtag.session import ScanSession

__all__ = [
    "ProgramOutcome",
    "ProgrammingFailed",
    "load_over_jtag",
]

# The startup sequence needs clocks after jstart; the guide asks for at least
# a dozen and the lab has always used this many.
_STARTUP_CLOCKS = 32
_INIT_CLOCKS = 64
_DONE_BIT = 1 << 14
_STATUS_BITS = 32


class ProgrammingFailed(TransientBenchError):
    """The device did not come up configured.

    Retryable, because the usual cause is the cable rather than the image, and
    the gates upstream have already ruled out most of the reasons an image
    would be wrong for a board.
    """

    code = "programming"


@dataclass(frozen=True, slots=True)
class ProgramOutcome:
    """What one load did."""

    board: str
    label: str
    bytes_sent: int
    clocks: int
    done: bool
    status: int = 0

    def describe(self) -> str:
        state = "done" if self.done else "not done"
        return f"{self.board} {self.label}: {self.bytes_sent} bytes, {self.clocks} clocks, {state}"


def load_over_jtag(
    session: ScanSession,
    image: bytes,
    *,
    label: str = "u1",
    board: str = "",
    swap: bool = True,
) -> ProgramOutcome:
    """Put an image into one device on the chain and report what happened."""
    if not image:
        raise BenchError("nothing to load")
    session.chain.index_of(label)
    before = session.clocks
    session.reset()
    session.load_instruction(label, "jprogram")
    session.run_test_idle(_INIT_CLOCKS)
    session.load_instruction(label, "cfg_in")
    payload = reverse_bits(image) if swap else image
    session.shift_dr(label, BitVector.from_bytes(payload))
    session.load_instruction(label, "jstart")
    session.run_test_idle(_STARTUP_CLOCKS)
    session.load_instruction(label, "cfg_out")
    status = session.shift_dr(label, BitVector.zeros(_STATUS_BITS))
    done = bool(status.value & _DONE_BIT)
    outcome = ProgramOutcome(
        board=board or label,
        label=label,
        bytes_sent=len(image),
        clocks=session.clocks - before,
        done=done,
        status=status.value,
    )
    if not done:
        raise ProgrammingFailed(
            "the device did not come up configured",
            board=outcome.board,
            status=f"0x{status.value:08x}",
            bytes_sent=outcome.bytes_sent,
        )
    return outcome
