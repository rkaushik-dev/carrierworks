"""Working out what is on a chain from the bits that came back.

After a reset every device loads its idcode into the data register, except the
ones that have no idcode, which sit in bypass instead. So a data register scan
with ones going in reads back as a run of thirty two bit idcodes with single
zero bits wherever a device had nothing to say. The two cases are told apart by
one bit: the lowest bit of an idcode is always one, and the bypass register
always captures zero.

The end of the chain is a run of ones, because that is what was shifted in and
nothing shifted it back out again. Thirty two of them in a row means every
device has been accounted for.

Nearest TDO comes back first, so the walk here produces the chain in the order
the bits arrived, and the caller reverses it to get the order a schematic
reads.
"""

from __future__ import annotations

from dataclasses import dataclass

from fpga.catalog.devices import all_devices
from fpga.catalog.idcode import IdCode
from fpga.errors import ChainError
from fpga.jtag.bits import BitVector
from fpga.jtag.chain import ScanChain, TapPosition

__all__ = [
    "DiscoveredTap",
    "chain_from_scan",
    "discover_taps",
    "scan_bits_for",
]

IDCODE_BITS = 32
_MAX_DEVICES = 32


@dataclass(frozen=True, slots=True)
class DiscoveredTap:
    """One device the scan found, in the order the bits arrived."""

    idcode: IdCode | None
    device: str | None
    ir_length: int | None

    @property
    def in_bypass(self) -> bool:
        return self.idcode is None

    @property
    def recognised(self) -> bool:
        return self.device is not None

    def describe(self) -> str:
        if self.idcode is None:
            return "bypass"
        return f"{self.device or 'unknown'} {self.idcode}"


def discover_taps(captured: BitVector) -> tuple[DiscoveredTap, ...]:
    """Read a chain out of a raw capture, nearest TDO first."""
    found: list[DiscoveredTap] = []
    offset = 0
    total = len(captured)
    while offset < total:
        remaining = total - offset
        if captured.bit(offset) == 0:
            found.append(DiscoveredTap(idcode=None, device=None, ir_length=None))
            offset += 1
        else:
            if remaining < IDCODE_BITS:
                raise ChainError(
                    "chain ended part way through an idcode",
                    read=str(captured.slice(offset, remaining)),
                )
            word = captured.slice(offset, IDCODE_BITS)
            code = IdCode(word.value)
            if code.is_floating:
                break
            if not code.is_plausible:
                raise ChainError("chain returned something that is not an idcode", idcode=str(code))
            spec = next(
                (known for known in all_devices() if known.matches_idcode(code.value)),
                None,
            )
            found.append(
                DiscoveredTap(
                    idcode=code,
                    device=spec.device if spec else None,
                    ir_length=spec.ir_length if spec else None,
                )
            )
            offset += IDCODE_BITS
        if len(found) > _MAX_DEVICES:
            raise ChainError("more devices than the bench believes in", found=len(found))
    if not found:
        raise ChainError("nothing answered on the chain")
    return tuple(found)


def chain_from_scan(captured: BitVector) -> ScanChain:
    """A usable chain description, in TDI order, from a discovery scan.

    Every device has to be one the catalog knows, because an unknown part has
    an unknown instruction register width and nothing can be shifted past it
    safely. That is a configuration job, not something to guess at.
    """
    found = discover_taps(captured)
    unknown = [tap for tap in found if tap.ir_length is None]
    if unknown:
        raise ChainError(
            "chain holds a device the catalog does not know",
            idcodes=[tap.describe() for tap in unknown],
        )
    positions = []
    for number, tap in enumerate(reversed(found), start=1):
        idcode, ir_length = tap.idcode, tap.ir_length
        if idcode is None or ir_length is None:  # pragma: no cover - filtered above
            raise ChainError("a device in bypass cannot be placed on a chain", position=number)
        positions.append(
            TapPosition(
                label=f"u{number}",
                ir_length=ir_length,
                device=tap.device,
                expected_idcode=idcode.without_version().value,
            )
        )
    return ScanChain(tuple(positions))


def scan_bits_for(chain: ScanChain) -> BitVector:
    """What a healthy discovery scan of this chain would read back.

    Used by the fake adapter and by the tests, and it is the only honest way
    to check the reader without a board on the bench.
    """
    answers = [
        BitVector.zeros(1)
        if tap.expected_idcode is None
        else BitVector(tap.expected_idcode, IDCODE_BITS)
        for tap in chain.in_tdo_order()
    ]
    return BitVector.join([*answers, BitVector.ones(IDCODE_BITS)])
