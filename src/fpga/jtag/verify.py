"""Checking a chain against the one the bench thinks is there.

The inventory says what a board carries. The scan says what answered. When they
disagree the useful output is not a boolean, it is which position disagreed and
how, because the three common causes look completely different in that detail.
A board that came back from rework with the wrong part fitted gives one
mismatched idcode at one position. A ribbon plugged into the wrong header gives
a chain of the right length with everything in the wrong order. A cable that
has worked loose gives no chain at all.

The whole capture is worth a look before any of that. All ones and all zeros
are both wiring stories rather than device stories, and saying so saves the
next person from reading a list of thirty two missing devices.
"""

from __future__ import annotations

from dataclasses import dataclass

from fpga.catalog.idcode import ALL_ONES, IdCode
from fpga.errors import ChainError
from fpga.jtag.bits import BitVector
from fpga.jtag.chain import ScanChain
from fpga.jtag.discover import chain_from_scan

__all__ = [
    "ChainDifference",
    "compare_chains",
    "diagnose_capture",
    "verify_chain",
]


@dataclass(frozen=True, slots=True)
class ChainDifference:
    """One way the chain that answered is not the chain that was expected."""

    kind: str
    position: int | None
    expected: str
    found: str

    def describe(self) -> str:
        where = "the chain" if self.position is None else f"position {self.position + 1}"
        if self.kind == "length":
            return f"{where} has {self.found} devices, expected {self.expected}"
        if self.kind == "order":
            return f"{where} holds {self.found}, which belongs elsewhere on this chain"
        return f"{where} holds {self.found}, expected {self.expected}"


def diagnose_capture(captured: BitVector) -> str | None:
    """A wiring level reading of a raw capture, or nothing if it looks sane."""
    if len(captured) == 0:
        return "nothing came back at all"
    if captured.value == 0:
        return "tdo stayed low, which is a chain held in reset or a board with no power"
    if captured == BitVector.ones(len(captured)):
        return "tdo stayed high, which is an open chain or a cable that is not seated"
    if len(captured) >= 32 and IdCode(captured.slice(0, 32).value).value == ALL_ONES:
        return "the first device did not answer"
    return None


def _label_for(device: str | None, idcode: int | None) -> str:
    if device:
        return device
    if idcode is not None:
        return str(IdCode(idcode))
    return "nothing"


def compare_chains(expected: ScanChain, found: ScanChain) -> tuple[ChainDifference, ...]:
    """Every way two chains differ, position by position."""
    differences: list[ChainDifference] = []
    if len(expected) != len(found):
        differences.append(
            ChainDifference(
                kind="length",
                position=None,
                expected=str(len(expected)),
                found=str(len(found)),
            )
        )
    expected_devices = [tap.device for tap in expected.taps]
    for index in range(min(len(expected), len(found))):
        want = expected.at(index)
        have = found.at(index)
        if want.device == have.device:
            continue
        kind = "order" if have.device in expected_devices else "device"
        differences.append(
            ChainDifference(
                kind=kind,
                position=index,
                expected=_label_for(want.device, want.expected_idcode),
                found=_label_for(have.device, have.expected_idcode),
            )
        )
    return tuple(differences)


def verify_chain(expected: ScanChain, captured: BitVector) -> ScanChain:
    """Read a capture, check it against the expected chain, and hand it back.

    Raises rather than returning a verdict, because every caller of this either
    goes on to program the board or stops, and there is nothing sensible in
    between.
    """
    wiring = diagnose_capture(captured)
    if wiring is not None:
        raise ChainError(wiring, expected=expected.describe())
    found = chain_from_scan(captured)
    differences = compare_chains(expected, found)
    if differences:
        raise ChainError(
            "the chain is not the one on file",
            expected=expected.describe(),
            found=found.describe(),
            differences=[difference.describe() for difference in differences],
        )
    return found
