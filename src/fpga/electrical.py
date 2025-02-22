"""Volts, amps and the windows a rail has to stay inside.

Everything is millivolts and milliamps as whole numbers. A rail that reads
1.7994 volts is 1799 millivolts and the fourth decimal place is the meter's
opinion, not the board's. Keeping it integer also means a tolerance window has
exact edges, so a reading that sits precisely on the limit lands the same way
every time instead of depending on which way the last floating point operation
rounded.

Notation is the other thing this module exists for. The lab writes rails as
3v3, the schematic writes 3.3V and the supply web page writes 3.30 V. They all
mean the same rail and all three arrive here. Text is always volts and a plain
integer is always millivolts, which is the one rule that keeps a config file
from asking for a three thousand volt rail.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from fpga.errors import ValidationError
from fpga.ids import slug

__all__ = [
    "RailReading",
    "RailSpec",
    "milliwatts",
    "parse_current",
    "parse_voltage",
]

_VOLTAGE = re.compile(
    r"""
    ^(?:
        (?P<whole>\d+) v (?P<fraction>\d{1,3})?
      | (?P<decimal>\d+(?:\.\d{1,3})?) \s* v?
    )$
    """,
    re.VERBOSE,
)

_CURRENT = re.compile(r"^(?P<value>\d+(?:\.\d{1,3})?)\s*(?P<unit>ma|a)?$")


def parse_voltage(text: str | int) -> int:
    """A rail voltage in millivolts, however the lab wrote it down."""
    if isinstance(text, int):
        if text < 0:
            raise ValidationError("negative voltage", field="voltage", given=text)
        return text
    cleaned = text.strip().lower().replace(" ", "")
    if not cleaned:
        raise ValidationError("empty voltage", field="voltage")
    match = _VOLTAGE.match(cleaned)
    if match is None:
        raise ValidationError("cannot read a voltage from " + repr(text), field="voltage")
    if match.group("whole") is not None:
        fraction = (match.group("fraction") or "").ljust(3, "0")
        return int(match.group("whole")) * 1000 + int(fraction or 0)
    whole, _, fraction = match.group("decimal").partition(".")
    return int(whole) * 1000 + int(fraction.ljust(3, "0") or 0)


def parse_current(text: str | int) -> int:
    """A current in milliamps."""
    if isinstance(text, int):
        if text < 0:
            raise ValidationError("negative current", field="current", given=text)
        return text
    cleaned = text.strip().lower().replace(" ", "")
    match = _CURRENT.match(cleaned)
    if match is None:
        raise ValidationError("cannot read a current from " + repr(text), field="current")
    unit = match.group("unit") or "ma"
    whole, _, fraction = match.group("value").partition(".")
    thousandths = int(whole) * 1000 + int(fraction.ljust(3, "0") or 0)
    if unit == "a":
        return thousandths
    if thousandths % 1000:
        raise ValidationError("milliamps are whole numbers", field="current", given=text)
    return thousandths // 1000


def milliwatts(millivolts: int, milliamps: int) -> int:
    """Power, rounded to the nearest whole milliwatt."""
    if millivolts < 0 or milliamps < 0:
        raise ValidationError("power is not defined for negative readings", field="power")
    return round(millivolts * milliamps / 1000)


@dataclass(frozen=True, slots=True)
class RailSpec:
    """One supply rail on a board, with the window it has to hold."""

    name: str
    nominal_mv: int
    tolerance_percent: int
    max_current_ma: int
    sequence: int = 0
    adjustable: bool = False

    def __post_init__(self) -> None:
        object.__setattr__(self, "name", slug(self.name))
        if self.nominal_mv <= 0:
            raise ValidationError("a rail has a positive nominal", field="nominal_mv")
        if not 1 <= self.tolerance_percent <= 25:
            raise ValidationError("tolerance is out of range", field="tolerance_percent")
        if self.max_current_ma <= 0:
            raise ValidationError("a rail carries some current", field="max_current_ma")

    @property
    def low_limit_mv(self) -> int:
        """Rounded inward, so a rail on the edge is judged against the tighter number."""
        return -(-self.nominal_mv * (100 - self.tolerance_percent) // 100)

    @property
    def high_limit_mv(self) -> int:
        return self.nominal_mv * (100 + self.tolerance_percent) // 100

    @property
    def max_power_mw(self) -> int:
        return milliwatts(self.high_limit_mv, self.max_current_ma)

    def contains(self, millivolts: int) -> bool:
        return self.low_limit_mv <= millivolts <= self.high_limit_mv

    def deviation_permille(self, millivolts: int) -> int:
        """How far off nominal a reading is, in parts per thousand.

        Signed, so a sagging rail and an overshooting one do not look alike.
        """
        return round((millivolts - self.nominal_mv) * 1000 / self.nominal_mv)

    def reading(self, millivolts: int, milliamps: int = 0) -> RailReading:
        return RailReading(rail=self.name, millivolts=millivolts, milliamps=milliamps, spec=self)


@dataclass(frozen=True, slots=True)
class RailReading:
    """What a rail measured at one moment."""

    rail: str
    millivolts: int
    milliamps: int
    spec: RailSpec

    @property
    def in_window(self) -> bool:
        return self.spec.contains(self.millivolts)

    @property
    def over_current(self) -> bool:
        return self.milliamps > self.spec.max_current_ma

    @property
    def power_mw(self) -> int:
        return milliwatts(self.millivolts, self.milliamps)

    @property
    def sagging(self) -> bool:
        return self.millivolts < self.spec.low_limit_mv

    def fault(self) -> str | None:
        """The one word for what is wrong, or nothing when the rail is fine."""
        if self.over_current:
            return "over_current"
        if self.sagging:
            return "under_voltage"
        if not self.in_window:
            return "over_voltage"
        return None

    def __str__(self) -> str:
        volts = f"{self.millivolts / 1000:.3f}".rstrip("0").rstrip(".")
        return f"{self.rail} {volts}V {self.milliamps}mA"
