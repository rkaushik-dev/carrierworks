"""Die temperature, in the units the silicon actually reports it in.

The system monitor on a 7 series part hands back a twelve bit code, not a
temperature. Turning one into the other is a fixed affine formula from the user
guide, and it is here rather than in the driver because the same conversion has
to run over stored codes when somebody asks what the board was doing an hour
ago.

Everything is millidegrees celsius. The sensor resolution is about a eighth of
a degree, so tenths lose real information and floats invite a comparison that
is true on one machine and false on another.

The part worth reading twice is the hysteresis. A board sitting exactly on the
warning threshold will cross it hundreds of times a minute, and every crossing
would be an alert, a log line and possibly a preempted lease. So the state only
climbs at the threshold and only falls once the die is a set margin below it.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from fpga.errors import ValidationError

__all__ = [
    "STATES",
    "ThermalLimits",
    "from_xadc_code",
    "parse_temperature",
    "render_temperature",
    "to_xadc_code",
]

# Ordered coldest to hottest. The order is the escalation.
STATES: tuple[str, ...] = ("normal", "warn", "critical", "shutdown")

_TEMPERATURE = re.compile(r"^(?P<sign>-?)(?P<whole>\d+)(?:\.(?P<fraction>\d{1,3}))?\s*(?:c|degc)?$")

# The 7 series system monitor transfer function, held as integers: the reading
# in millidegrees is code * 503975 / 4096 - 273150.
_XADC_SCALE_NUMERATOR = 503_975
_XADC_SCALE_DENOMINATOR = 4096
_XADC_OFFSET_MC = 273_150
_XADC_FULL_SCALE = 0xFFF


def parse_temperature(text: str | int | float) -> int:
    """A temperature in millidegrees, from whatever wrote it down."""
    if isinstance(text, int):
        return text * 1000
    if isinstance(text, float):
        return round(text * 1000)
    cleaned = text.strip().lower().replace(" ", "").rstrip("°")
    if not cleaned:
        raise ValidationError("empty temperature", field="temperature")
    match = _TEMPERATURE.match(cleaned)
    if match is None:
        raise ValidationError("cannot read a temperature from " + repr(text), field="temperature")
    fraction = (match.group("fraction") or "").ljust(3, "0")
    millidegrees = int(match.group("whole")) * 1000 + int(fraction or 0)
    return -millidegrees if match.group("sign") else millidegrees


def render_temperature(millidegrees: int) -> str:
    """Back to one decimal place, which is what a person wants to read."""
    return f"{millidegrees / 1000:.1f}C"


def from_xadc_code(code: int) -> int:
    """A raw system monitor code as millidegrees celsius."""
    if not 0 <= code <= _XADC_FULL_SCALE:
        raise ValidationError("system monitor codes are twelve bits", field="code", given=code)
    scaled = code * _XADC_SCALE_NUMERATOR // _XADC_SCALE_DENOMINATOR
    return scaled - _XADC_OFFSET_MC


def to_xadc_code(millidegrees: int) -> int:
    """The code the part would report for a temperature, for fakes and tests."""
    scaled = (millidegrees + _XADC_OFFSET_MC) * _XADC_SCALE_DENOMINATOR
    code = round(scaled / _XADC_SCALE_NUMERATOR)
    if not 0 <= code <= _XADC_FULL_SCALE:
        raise ValidationError(
            "temperature is outside what the sensor can report",
            field="temperature",
            given=millidegrees,
        )
    return code


@dataclass(frozen=True, slots=True)
class ThermalLimits:
    """Where the bench draws its lines for one board."""

    warn_mc: int
    critical_mc: int
    shutdown_mc: int
    hysteresis_mc: int = 3000

    def __post_init__(self) -> None:
        if not self.warn_mc < self.critical_mc < self.shutdown_mc:
            raise ValidationError(
                "thermal thresholds have to climb",
                field="thresholds",
                warn=self.warn_mc,
                critical=self.critical_mc,
                shutdown=self.shutdown_mc,
            )
        if not 0 < self.hysteresis_mc <= 10_000:
            raise ValidationError("hysteresis is out of range", field="hysteresis_mc")

    @classmethod
    def from_junction_range(cls, junction: tuple[int, int], **overrides: int) -> ThermalLimits:
        """Limits derived from the part's own maximum junction temperature.

        Shutdown sits on the datasheet maximum because past it the part is out
        of specification whatever the bench thinks. The two below it are margin
        the lab chose: ten degrees to warn, four to escalate.
        """
        top = junction[1] * 1000
        limits = {
            "warn_mc": top - 14_000,
            "critical_mc": top - 4_000,
            "shutdown_mc": top,
        }
        limits.update(overrides)
        return cls(**limits)

    def state_for(self, millidegrees: int) -> str:
        """The state a cold board would go straight to at this temperature."""
        if millidegrees >= self.shutdown_mc:
            return "shutdown"
        if millidegrees >= self.critical_mc:
            return "critical"
        if millidegrees >= self.warn_mc:
            return "warn"
        return "normal"

    def threshold_for(self, state: str) -> int:
        if state == "shutdown":
            return self.shutdown_mc
        if state == "critical":
            return self.critical_mc
        if state == "warn":
            return self.warn_mc
        raise ValidationError("no threshold for that state", field="state", given=state)

    def next_state(self, current: str, millidegrees: int) -> str:
        """Where the board goes next, with the hysteresis applied on the way down.

        Climbing is immediate because a hot part does not get the benefit of
        the doubt. Falling needs the die to be a whole hysteresis band below
        the threshold it crossed, so an alert does not chatter.
        """
        if current not in STATES:
            raise ValidationError("unknown thermal state", field="state", given=current)
        raw = self.state_for(millidegrees)
        if STATES.index(raw) >= STATES.index(current):
            return raw
        settled = current
        while settled != "normal":
            threshold = self.threshold_for(settled)
            if millidegrees > threshold - self.hysteresis_mc:
                break
            settled = STATES[STATES.index(settled) - 1]
        return settled if STATES.index(settled) >= STATES.index(raw) else raw
