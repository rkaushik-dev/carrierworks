"""Ordering codes, taken apart.

An ordering code like ``XC7A100T-2FGG484I`` is five facts glued together with
no separators, and the lab needs all five for different reasons. The device
decides which bitstream will load. The speed grade decides whether a timing
report from one board means anything on another. The package decides which
carrier the board can be. The temperature grade decides how hot the thermal
supervisor is allowed to let it get. The pin count is how anyone tells two
otherwise identical boards apart across the bench.

Only Xilinx codes are parsed here. Lattice and Intel arrive later and they do
not follow this grammar at all, which is why the parser is not written as if
one regular expression could cover the world.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from fpga.errors import ValidationError

__all__ = [
    "TEMPERATURE_RANGES",
    "PartNumber",
    "parse_part_number",
]

# Junction temperature range in celsius for each ordering code suffix. These
# are the datasheet numbers, and the thermal supervisor reads them rather than
# carrying a threshold of its own.
TEMPERATURE_RANGES: dict[str, tuple[int, int]] = {
    "c": (0, 85),
    "i": (-40, 100),
    "e": (0, 100),
    "q": (-40, 125),
    "m": (-55, 125),
}

_DEVICE = re.compile(
    r"""
    ^xc
    (?:
        7 (?P<seven>[akvsz]) (?P<seven_size>\d+ [a-z]*)
      | 6 s (?P<six>lx|vlx) (?P<six_size>\d+ t?)
      | (?P<ultra>ku|vu|zu|au) (?P<ultra_size>\d+ [a-z]*)
    )
    $
    """,
    re.VERBOSE,
)

_ORDERING = re.compile(
    r"""
    ^(?P<device>xc[a-z0-9]+?)
    -
    (?P<speed>\d l?)
    (?P<package>[a-z]+ \d+)
    (?P<temperature>[cieqm])
    $
    """,
    re.VERBOSE,
)

_SEVEN_FAMILIES = {
    "a": "artix7",
    "k": "kintex7",
    "v": "virtex7",
    "s": "spartan7",
    "z": "zynq7000",
}

_ULTRA_FAMILIES = {
    "ku": "kintex_ultrascale",
    "vu": "virtex_ultrascale",
    "zu": "zynq_ultrascale",
    "au": "artix_ultrascale",
}


def _family_of(device: str) -> str:
    match = _DEVICE.match(device)
    if match is None:
        raise ValidationError("not a xilinx device code: " + device, field="device")
    if match.group("seven"):
        return _SEVEN_FAMILIES[match.group("seven")]
    if match.group("six"):
        return "spartan6"
    return _ULTRA_FAMILIES[match.group("ultra")]


@dataclass(frozen=True, slots=True)
class PartNumber:
    """One ordering code, held apart at the seams.

    Everything is stored lower case. The bench compares these constantly and
    half the sources of truth shout their part numbers while the other half
    whisper them.
    """

    device: str
    speed_grade: str
    package: str
    temperature: str

    def __post_init__(self) -> None:
        if self.temperature not in TEMPERATURE_RANGES:
            raise ValidationError(
                "unknown temperature grade " + repr(self.temperature),
                field="temperature",
                device=self.device,
            )
        _family_of(self.device)

    @property
    def family(self) -> str:
        return _family_of(self.device)

    @property
    def vendor(self) -> str:
        return "xilinx"

    @property
    def pin_count(self) -> int:
        digits = re.search(r"\d+$", self.package)
        if digits is None:  # pragma: no cover - the parser will not build one
            raise ValidationError("package has no pin count", field="package")
        return int(digits.group())

    @property
    def low_power(self) -> bool:
        """The L screens are the low static power bins."""
        return "l" in self.speed_grade

    @property
    def junction_range(self) -> tuple[int, int]:
        return TEMPERATURE_RANGES[self.temperature]

    @property
    def speed_rank(self) -> int:
        """Higher is faster. A 2L sits below a plain 2 and above a 1."""
        digit = int(self.speed_grade[0])
        return digit * 2 - (1 if self.low_power else 0)

    def ordering_code(self) -> str:
        """Back to the form printed on the lid."""
        body = self.device + "-" + self.speed_grade + self.package + self.temperature
        return body.upper()

    def fits(self, other: PartNumber) -> bool:
        """Whether a board fitted with this part can stand in for another.

        Same silicon and same package, and at least the speed the caller asked
        for. A faster part runs a slower one's bitstream; the reverse is how a
        regression turns into a week of confusion.
        """
        if (self.device, self.package) != (other.device, other.package):
            return False
        return self.speed_rank >= other.speed_rank

    def __str__(self) -> str:
        return self.ordering_code()


def parse_part_number(text: str) -> PartNumber:
    """Take an ordering code apart, however it was typed."""
    cleaned = text.strip().lower().replace(" ", "")
    if not cleaned:
        raise ValidationError("empty part number", field="part_number")
    match = _ORDERING.match(cleaned)
    if match is None:
        raise ValidationError("cannot read part number " + repr(text), field="part_number")
    return PartNumber(
        device=match.group("device"),
        speed_grade=match.group("speed"),
        package=match.group("package"),
        temperature=match.group("temperature"),
    )
