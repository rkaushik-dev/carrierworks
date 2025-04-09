"""The boundary between everything above and the cable driver underneath.

The interface is deliberately one call wide. A transport clocks a run of TMS
and TDI bits and hands back what came out of TDO, and it does not know what a
chain is, what an instruction is, or which device anything belongs to. Every
driver the lab has ever had could be written against that, including the two
that talk to a vendor tool over a pipe rather than to a cable.

Recording matters as much as driving. When a programming job fails at two in
the morning, the thing worth having is the exact sequence that went out, which
is why the recorder wraps any transport rather than being a feature of one.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol, runtime_checkable

from fpga.errors import ValidationError
from fpga.jtag.bits import BitVector
from fpga.measure import Frequency

__all__ = [
    "LoopbackTransport",
    "RecordingTransport",
    "ScanOperation",
    "Transport",
]


@runtime_checkable
class Transport(Protocol):
    """Whatever can clock bits at a chain."""

    @property
    def name(self) -> str: ...

    def set_clock(self, clock: Frequency) -> None: ...

    def pulse(self, tms: BitVector, tdi: BitVector) -> BitVector: ...

    def close(self) -> None: ...


@dataclass(frozen=True, slots=True)
class ScanOperation:
    """One clocked run, kept for the journal."""

    tms: BitVector
    tdi: BitVector
    tdo: BitVector

    @property
    def clocks(self) -> int:
        return len(self.tms)

    def describe(self) -> str:
        return f"{self.clocks} clocks tms={self.tms} tdi={self.tdi} tdo={self.tdo}"


def _check_pair(tms: BitVector, tdi: BitVector) -> None:
    if len(tms) != len(tdi):
        raise ValidationError(
            "tms and tdi have to be the same length",
            field="pulse",
            tms=len(tms),
            tdi=len(tdi),
        )
    if not len(tms):
        raise ValidationError("a pulse clocks at least one bit", field="pulse")


@dataclass(slots=True)
class LoopbackTransport:
    """A transport that returns TDI unchanged, one clock later.

    Not a simulation of anything, and it will not answer a real scan. It is
    here so that code paths which only need a transport to exist can have one,
    and so the recorder has something to wrap in its own tests.
    """

    clock: Frequency = field(default_factory=lambda: Frequency.megahertz(1))
    closed: bool = False

    @property
    def name(self) -> str:
        return "loopback"

    def set_clock(self, clock: Frequency) -> None:
        self.clock = clock

    def pulse(self, tms: BitVector, tdi: BitVector) -> BitVector:
        if self.closed:
            raise ValidationError("transport is closed", field="transport")
        _check_pair(tms, tdi)
        return tdi

    def close(self) -> None:
        self.closed = True


@dataclass(slots=True)
class RecordingTransport:
    """Wraps a transport and keeps every operation that went through it."""

    inner: Transport
    operations: list[ScanOperation] = field(default_factory=list)
    limit: int = 4096

    @property
    def name(self) -> str:
        return f"recording({self.inner.name})"

    def set_clock(self, clock: Frequency) -> None:
        self.inner.set_clock(clock)

    def pulse(self, tms: BitVector, tdi: BitVector) -> BitVector:
        tdo = self.inner.pulse(tms, tdi)
        if len(self.operations) < self.limit:
            self.operations.append(ScanOperation(tms=tms, tdi=tdi, tdo=tdo))
        return tdo

    def close(self) -> None:
        self.inner.close()

    @property
    def clocks(self) -> int:
        """Every clock that has gone out, which is what a job reports."""
        return sum(operation.clocks for operation in self.operations)

    @property
    def truncated(self) -> bool:
        return len(self.operations) >= self.limit

    def transcript(self) -> tuple[str, ...]:
        lines = tuple(operation.describe() for operation in self.operations)
        if self.truncated:
            return (*lines, "transcript truncated")
        return lines

    def clear(self) -> None:
        self.operations.clear()
