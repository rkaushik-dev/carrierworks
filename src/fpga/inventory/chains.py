"""The chain a board ought to present, and what to do when it does not.

Most carriers put one device on the header and that is the whole chain. Two of
the ones in this lab do not: the capture carriers have a small system
controller in front of the FPGA, and the older test rig has a pair of them.
Those are not devices the bench stocks, because nothing ever loads a bitstream
into one, but they are on the chain and every scan has to shift past them.

So the extra positions are declared per carrier model, with the idcode they
answer with and the width of their instruction register, and that is all the
bench needs to know about them.

Reconciliation is the other half. A scan that does not match is not one
problem, it is three different problems with three different answers, and the
result says which: a board with the wrong part fitted goes to maintenance, a
chain that does not answer at all is usually a cable and gets retried, and a
chain in the wrong order means somebody has plugged into the wrong header.
"""

from __future__ import annotations

from dataclasses import dataclass

from fpga.catalog.idcode import IdCode
from fpga.errors import ChainError
from fpga.inventory.boards import Board
from fpga.jtag.bits import BitVector
from fpga.jtag.chain import ScanChain, TapPosition
from fpga.jtag.discover import DiscoveredTap, discover_taps
from fpga.jtag.verify import ChainDifference, diagnose_capture

__all__ = [
    "CARRIER_TAPS",
    "ChainCheck",
    "chain_for",
    "reconcile",
]

# Positions that sit on the header in front of the device, by carrier model.
# Each is (label, ir length, idcode) in TDI order.
CARRIER_TAPS: dict[str, tuple[tuple[str, int, int], ...]] = {
    "rx-carrier-b": (("sysctl", 8, 0x06E5E093),),
    "rx-carrier-a": (("sysctl", 8, 0x06E5E093),),
    "hil-rig-1": (("sysctl", 8, 0x06E5E093), ("pmc", 8, 0x06E1C093)),
}


def chain_for(board: Board) -> ScanChain:
    """The chain this board should present, controllers and all."""
    positions = [
        TapPosition(label=label, ir_length=ir_length, expected_idcode=idcode)
        for label, ir_length, idcode in CARRIER_TAPS.get(board.model, ())
    ]
    positions.append(TapPosition.for_device("u1", board.device().device))
    return ScanChain(tuple(positions))


@dataclass(frozen=True, slots=True)
class ChainCheck:
    """What a scan of one board's chain came to."""

    board: str
    expected: str
    wiring: str | None = None
    differences: tuple[ChainDifference, ...] = ()
    found: str = ""

    @property
    def ok(self) -> bool:
        return self.wiring is None and not self.differences

    @property
    def fault_kind(self) -> str | None:
        """Which kind of fault to record, or nothing when the board is fine."""
        return None if self.ok else "chain"

    @property
    def retry_worthwhile(self) -> bool:
        """A chain that did not answer is worth another go; a wrong one is not."""
        return self.wiring is not None

    def next_state(self) -> str | None:
        """Where the board should go, or nothing to leave it where it is."""
        if self.ok:
            return None
        if self.wiring is not None:
            return "faulted"
        return "maintenance"

    def describe(self) -> str:
        if self.ok:
            return f"{self.board} chain matches {self.expected}"
        if self.wiring is not None:
            return f"{self.board} {self.wiring}"
        reasons = "; ".join(difference.describe() for difference in self.differences)
        return f"{self.board} chain does not match: {reasons}"


def _label(idcode: int | None, device: str | None) -> str:
    if device:
        return device
    if idcode is None:
        return "bypass"
    return str(IdCode(idcode))


def _differences(
    expected: ScanChain,
    found: tuple[DiscoveredTap, ...],
) -> tuple[ChainDifference, ...]:
    """Compare position by position on idcode, in TDI order.

    Not the general chain comparison, because half the positions on these
    boards are controllers the catalog has never heard of. An idcode is enough
    to say whether the right thing is in the right place.
    """
    wanted = expected.taps
    differences: list[ChainDifference] = []
    if len(wanted) != len(found):
        differences.append(
            ChainDifference(
                kind="length",
                position=None,
                expected=str(len(wanted)),
                found=str(len(found)),
            )
        )
    expected_codes = [
        None if tap.expected_idcode is None else IdCode(tap.expected_idcode).without_version().value
        for tap in wanted
    ]
    for index in range(min(len(wanted), len(found))):
        tap = wanted[index]
        answered = found[index]
        code = None if answered.idcode is None else answered.idcode.without_version().value
        if code == expected_codes[index]:
            continue
        kind = "order" if code in expected_codes else "device"
        differences.append(
            ChainDifference(
                kind=kind,
                position=index,
                expected=_label(tap.expected_idcode, tap.device),
                found=_label(
                    None if answered.idcode is None else answered.idcode.value, answered.device
                ),
            )
        )
    return tuple(differences)


def reconcile(board: Board, captured: BitVector) -> ChainCheck:
    """Compare what a board answered with against what it should have."""
    expected = chain_for(board)
    wiring = diagnose_capture(captured)
    if wiring is not None:
        return ChainCheck(board=board.tag, expected=expected.describe(), wiring=wiring)
    try:
        answered = discover_taps(captured)
    except ChainError as failure:
        return ChainCheck(
            board=board.tag,
            expected=expected.describe(),
            wiring=failure.message,
        )
    found = tuple(reversed(answered))
    return ChainCheck(
        board=board.tag,
        expected=expected.describe(),
        differences=_differences(expected, found),
        found=" -> ".join(tap.describe() for tap in found),
    )
