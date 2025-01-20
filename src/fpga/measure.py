"""The units the bench argues in.

Three of them, and each one exists because somewhere a mistake was made in it.
Sizes are binary and always in bytes, because a flash sector is 65536 bytes and
nobody in the lab has ever meant 65000. Frequencies are whole hertz, because an
adapter that claims 30 MHz and runs at 30.000001 MHz is a rounding artefact and
not a faster cable. Times are picoseconds, because the timing reports are in
nanoseconds to three decimal places and floating point addition over four
thousand of those is how a slack total drifts.

Everything is integer inside. Formatting to something readable happens at the
edges and never round trips through a float on the way back.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from fpga.errors import ValidationError

__all__ = [
    "Frequency",
    "format_size",
    "kib",
    "mib",
    "parse_size",
    "picoseconds_from_ns",
    "render_ns",
]

_SIZE_UNITS: dict[str, int] = {
    "": 1,
    "b": 1,
    "byte": 1,
    "bytes": 1,
    "k": 1024,
    "kb": 1024,
    "kib": 1024,
    "m": 1024**2,
    "mb": 1024**2,
    "mib": 1024**2,
    "g": 1024**3,
    "gb": 1024**3,
    "gib": 1024**3,
}

_SIZE = re.compile(r"^(?P<value>\d+(?:\.\d+)?)\s*(?P<unit>[a-z]*)$")

_FREQUENCY_UNITS: dict[str, int] = {
    "": 1,
    "hz": 1,
    "khz": 1_000,
    "mhz": 1_000_000,
    "ghz": 1_000_000_000,
}

_FREQUENCY = re.compile(r"^(?P<value>\d+(?:\.\d+)?)\s*(?P<unit>[a-z]*)$")


def kib(count: int) -> int:
    return count * 1024


def mib(count: int) -> int:
    return count * 1024 * 1024


def parse_size(text: str) -> int:
    """Read a size written the way a datasheet writes it, in bytes.

    A bare number is bytes. Decimal values are allowed only where they land on
    a whole byte, so ``1.5 MiB`` is fine and ``1.5 B`` is not.
    """
    cleaned = text.strip().lower()
    match = _SIZE.match(cleaned)
    if match is None:
        raise ValidationError("cannot read a size from " + repr(text), field="size")
    unit = match.group("unit")
    if unit not in _SIZE_UNITS:
        raise ValidationError("unknown size unit " + repr(unit), field="size")
    scale = _SIZE_UNITS[unit]
    raw = match.group("value")
    if "." in raw:
        whole, _, fraction = raw.partition(".")
        scaled = int(whole) * scale + int(fraction) * scale // 10 ** len(fraction)
        exact = int(whole) * scale * 10 ** len(fraction) + int(fraction) * scale
        if exact % 10 ** len(fraction):
            raise ValidationError("size is not a whole number of bytes", field="size", given=text)
        return scaled
    return int(raw) * scale


def format_size(size: int) -> str:
    """Bytes back to something a person reads without counting zeros."""
    if size < 0:
        raise ValidationError("negative size", field="size", given=size)
    for unit, scale in (("GiB", 1024**3), ("MiB", 1024**2), ("KiB", 1024)):
        if size < scale:
            continue
        if size % scale == 0:
            return f"{size // scale} {unit}"
        return f"{size / scale:.1f} {unit}"
    return f"{size} B"


@dataclass(frozen=True, slots=True, order=True)
class Frequency:
    """A clock rate in whole hertz."""

    hertz: int

    def __post_init__(self) -> None:
        if self.hertz <= 0:
            raise ValidationError("frequency must be positive", field="hertz", given=self.hertz)

    @classmethod
    def kilohertz(cls, value: float) -> Frequency:
        return cls(round(value * 1_000))

    @classmethod
    def megahertz(cls, value: float) -> Frequency:
        return cls(round(value * 1_000_000))

    @classmethod
    def parse(cls, text: str) -> Frequency:
        cleaned = text.strip().lower().replace(" ", "")
        match = _FREQUENCY.match(cleaned)
        if match is None:
            raise ValidationError("cannot read a frequency from " + repr(text), field="frequency")
        unit = match.group("unit")
        if unit not in _FREQUENCY_UNITS:
            raise ValidationError("unknown frequency unit " + repr(unit), field="frequency")
        return cls(round(float(match.group("value")) * _FREQUENCY_UNITS[unit]))

    @property
    def megahertz_value(self) -> float:
        return self.hertz / 1_000_000

    @property
    def period_ps(self) -> int:
        """Clock period in picoseconds, rounded to the nearest whole one."""
        return round(1_000_000_000_000 / self.hertz)

    def divided_by(self, divisor: int) -> Frequency:
        """A divided clock, the way an adapter divides its own reference."""
        if divisor <= 0:
            raise ValidationError("divisor must be positive", field="divisor", given=divisor)
        return Frequency(self.hertz // divisor)

    def at_most(self, ceiling: Frequency) -> Frequency:
        """This clock, held down to whatever the slowest thing on the chain allows."""
        return self if self.hertz <= ceiling.hertz else ceiling

    def __str__(self) -> str:
        if self.hertz >= 1_000_000 and self.hertz % 1_000_000 == 0:
            return f"{self.hertz // 1_000_000} MHz"
        if self.hertz >= 1_000_000:
            return f"{self.hertz / 1_000_000:.3f} MHz"
        if self.hertz >= 1_000 and self.hertz % 1_000 == 0:
            return f"{self.hertz // 1_000} kHz"
        if self.hertz >= 1_000:
            return f"{self.hertz / 1_000:.3f} kHz"
        return f"{self.hertz} Hz"


def picoseconds_from_ns(text: str | float) -> int:
    """A nanosecond figure out of a report, as whole picoseconds.

    Timing reports print three decimal places, so a value with more than that
    is either a different tool or a typo. Both are worth refusing.
    """
    raw = f"{text:.6f}".rstrip("0").rstrip(".") if isinstance(text, int | float) else text.strip()
    if not raw:
        raise ValidationError("empty timing value", field="ns")
    negative = raw.startswith("-")
    if raw[0] in "+-":
        raw = raw[1:]
    if not re.match(r"^\d+(\.\d{1,3})?$", raw):
        raise ValidationError("cannot read nanoseconds from " + repr(text), field="ns")
    whole, _, fraction = raw.partition(".")
    picoseconds = int(whole) * 1000 + int(fraction.ljust(3, "0") or 0)
    return -picoseconds if negative else picoseconds


def render_ns(picoseconds: int) -> str:
    """Picoseconds back out in the three decimal form the reports use."""
    sign = "-" if picoseconds < 0 else ""
    value = abs(picoseconds)
    return f"{sign}{value // 1000}.{value % 1000:03d}"
