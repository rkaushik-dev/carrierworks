"""The cables between the bench and the boards, and how fast each may run.

An adapter has a headline clock on its box and that number is almost never the
one to use. The real ceiling is the lowest of four things: what the adapter can
do, what the site allows, what the board's own header is rated for, and what
the chain will stand. The last one is the interesting one, because every extra
device on the chain adds a buffer delay and the fastest chain in the lab is the
one with a single part on it.

Voltage is not negotiable in the same way. The adapter drives its outputs at
whatever the board tells it to on the reference pin, and an adapter that cannot
reach that level is simply the wrong cable. Finding that out in software beats
finding it out by watching a chain read back as noise.
"""

from __future__ import annotations

from dataclasses import dataclass

from fpga.errors import CatalogError, NotFound, ValidationError
from fpga.ids import adapter_serial, slug
from fpga.measure import Frequency

__all__ = [
    "AdapterSpec",
    "AdapterUnit",
    "adapter_model",
    "all_adapter_models",
    "negotiate_clock",
]

# Each extra device on the chain costs this much of the clock, and no chain is
# ever taken below the floor whatever the arithmetic says.
_DERATE_PERCENT_PER_DEVICE = 20
_CLOCK_FLOOR_HZ = 1_000_000


@dataclass(frozen=True, slots=True)
class AdapterSpec:
    """One model of programming cable."""

    model: str
    vendor: str
    max_clock_hz: int
    voltages_mv: tuple[int, ...]
    channels: int = 1
    buffered: bool = True
    can_program_flash: bool = True

    def __post_init__(self) -> None:
        object.__setattr__(self, "model", slug(self.model))
        if self.max_clock_hz <= 0:
            raise CatalogError("an adapter has a clock", adapter=self.model)
        if not self.voltages_mv:
            raise CatalogError("an adapter drives some voltage", adapter=self.model)
        if self.channels < 1:
            raise CatalogError("an adapter has at least one channel", adapter=self.model)

    @property
    def max_clock(self) -> Frequency:
        return Frequency(self.max_clock_hz)

    def drives(self, millivolts: int) -> bool:
        """Whether this cable can drive a bank at that level."""
        return millivolts in self.voltages_mv

    def lowest_voltage_mv(self) -> int:
        return min(self.voltages_mv)


_CATALOG: tuple[AdapterSpec, ...] = (
    AdapterSpec(
        model="jtag-hs2",
        vendor="digilent",
        max_clock_hz=30_000_000,
        voltages_mv=(1800, 2500, 3300, 5000),
    ),
    AdapterSpec(
        model="jtag-hs3",
        vendor="digilent",
        max_clock_hz=30_000_000,
        voltages_mv=(1800, 2500, 3300),
    ),
    AdapterSpec(
        model="jtag-smt2",
        vendor="digilent",
        max_clock_hz=30_000_000,
        voltages_mv=(1800, 2500, 3300),
    ),
    AdapterSpec(
        model="platform-cable-ii",
        vendor="xilinx",
        max_clock_hz=24_000_000,
        voltages_mv=(1500, 1800, 2500, 3300),
    ),
    AdapterSpec(
        model="ft2232h-mini",
        vendor="ftdi",
        max_clock_hz=30_000_000,
        voltages_mv=(3300,),
        channels=2,
        buffered=False,
    ),
    AdapterSpec(
        model="arm-usb-tiny-h",
        vendor="olimex",
        max_clock_hz=30_000_000,
        voltages_mv=(1800, 2500, 3300),
        can_program_flash=False,
    ),
)

_BY_MODEL = {spec.model: spec for spec in _CATALOG}


def adapter_model(model: str) -> AdapterSpec:
    spec = _BY_MODEL.get(slug(model))
    if spec is None:
        raise NotFound("adapter model", slug(model))
    return spec


def all_adapter_models() -> tuple[AdapterSpec, ...]:
    return _CATALOG


@dataclass(frozen=True, slots=True)
class AdapterUnit:
    """A particular cable, with the serial burned into it."""

    serial: str
    model: str
    port: str = ""

    def __post_init__(self) -> None:
        object.__setattr__(self, "serial", adapter_serial(self.serial))
        object.__setattr__(self, "model", slug(self.model))
        adapter_model(self.model)

    @property
    def spec(self) -> AdapterSpec:
        return adapter_model(self.model)

    def describe(self) -> str:
        where = f" on {self.port}" if self.port else ""
        return f"{self.model} {self.serial}{where}"


def negotiate_clock(
    adapter: AdapterSpec,
    *,
    devices: int,
    site_ceiling_hz: int,
    board_ceiling_hz: int | None = None,
) -> Frequency:
    """The clock to actually run, given everything that has an opinion.

    The derating is per device beyond the first and it compounds the simple
    way, by subtraction rather than by multiplication, because the number it
    produces has to be explainable to whoever asks why their board is slow.
    """
    if devices < 1:
        raise ValidationError("a chain has devices on it", field="devices", given=devices)
    if site_ceiling_hz <= 0:
        raise ValidationError("the site ceiling is a clock", field="site_ceiling_hz")
    ceiling = min(adapter.max_clock_hz, site_ceiling_hz)
    if board_ceiling_hz is not None:
        if board_ceiling_hz <= 0:
            raise ValidationError("the board ceiling is a clock", field="board_ceiling_hz")
        ceiling = min(ceiling, board_ceiling_hz)
    derate = min(_DERATE_PERCENT_PER_DEVICE * (devices - 1), 80)
    derated = ceiling * (100 - derate) // 100
    return Frequency(max(derated, min(_CLOCK_FLOOR_HZ, ceiling)))
