"""Daughtercards, the connectors they land on and what they bring with them.

A board on its own is a device and some LEDs. What makes it the right board for
a particular regression is whatever is bolted to the front of it, which is why
the scheduler matches on the capabilities a card provides rather than on the
card by name. Nobody asks for a specific converter module; they ask for two
channels of sixteen bit capture and do not care whose.

The connector rule is the one that catches people. A low pin count FMC card
drops into a high pin count carrier and works, because HPC is LPC plus more
signals. The reverse does not, and the card looks seated because the mechanical
part of the connector is identical. That single asymmetry is why this module
has a fit rule rather than an equality check.

VADJ is the other trap. The carrier drives the adjustable rail and the card
tells it what to drive; a card wanting 1.8 volts on a carrier fixed at 2.5 will
come up and behave strangely rather than refuse.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from fpga.catalog.index import CatalogIndex
from fpga.errors import CatalogError, NotFound
from fpga.ids import slug

__all__ = [
    "CARDS",
    "CONNECTORS",
    "CardSpec",
    "all_cards",
    "card",
    "cards_providing",
    "connector_accepts",
    "lookup_card",
]

# Every connector kind the lab has, and what each will physically accept.
CONNECTORS: dict[str, frozenset[str]] = {
    "fmc-hpc": frozenset({"fmc-hpc", "fmc-lpc"}),
    "fmc-lpc": frozenset({"fmc-lpc"}),
    "pmod": frozenset({"pmod"}),
    "sfp-cage": frozenset({"sfp-cage"}),
    "hdmi": frozenset({"hdmi"}),
}


def connector_accepts(carrier: str, card_connector: str) -> bool:
    """Whether a card with this edge fits a carrier with that socket."""
    accepted = CONNECTORS.get(carrier)
    if accepted is None:
        raise NotFound("connector", carrier)
    return card_connector in accepted


@dataclass(frozen=True, slots=True)
class CardSpec:
    """One module the lab can bolt to a carrier."""

    name: str
    vendor: str
    connector: str
    provides: frozenset[str]
    power_draw_mw: int
    vadj_millivolts: int | None = None
    transceivers_used: int = 0
    notes: str = ""
    tags: frozenset[str] = field(default_factory=frozenset)

    def __post_init__(self) -> None:
        if self.connector not in CONNECTORS:
            raise CatalogError("card has an unknown connector", card=self.name)
        if not self.provides:
            raise CatalogError("a card that provides nothing is not worth stocking", card=self.name)
        for capability in self.provides:
            if capability != slug(capability):
                raise CatalogError(
                    "capability names are slugs",
                    card=self.name,
                    capability=capability,
                )
        if self.power_draw_mw < 0:
            raise CatalogError("negative power draw", card=self.name)

    def fits(self, carrier_connector: str) -> bool:
        return connector_accepts(carrier_connector, self.connector)

    def provides_all(self, wanted: frozenset[str] | set[str]) -> bool:
        return set(wanted).issubset(self.provides)

    @property
    def needs_adjustable_rail(self) -> bool:
        return self.vadj_millivolts is not None


_CATALOG: tuple[CardSpec, ...] = (
    CardSpec(
        name="fmc-adc-2ch-16b",
        vendor="curtiss",
        connector="fmc-lpc",
        provides=frozenset({"adc-16bit", "adc-2ch", "external-clock"}),
        power_draw_mw=6500,
        vadj_millivolts=1800,
        notes="two channel 250 msps capture, needs the external clock fitted",
    ),
    CardSpec(
        name="fmc-adc-4ch-14b",
        vendor="curtiss",
        connector="fmc-hpc",
        provides=frozenset({"adc-14bit", "adc-4ch", "external-clock", "trigger-in"}),
        power_draw_mw=9800,
        vadj_millivolts=1800,
        transceivers_used=4,
    ),
    CardSpec(
        name="fmc-dac-2ch-16b",
        vendor="curtiss",
        connector="fmc-lpc",
        provides=frozenset({"dac-16bit", "dac-2ch"}),
        power_draw_mw=5200,
        vadj_millivolts=1800,
    ),
    CardSpec(
        name="fmc-sfp-quad",
        vendor="opsero",
        connector="fmc-hpc",
        provides=frozenset({"sfp-4port", "ethernet-10g", "gth-lanes"}),
        power_draw_mw=4100,
        vadj_millivolts=1800,
        transceivers_used=4,
    ),
    CardSpec(
        name="fmc-ddr4-sodimm",
        vendor="opsero",
        connector="fmc-hpc",
        provides=frozenset({"ddr4-sodimm", "memory-8gb"}),
        power_draw_mw=7400,
        vadj_millivolts=1200,
        notes="the rail has to come up at 1v2 or the dimm will not train",
    ),
    CardSpec(
        name="pmod-ad2",
        vendor="digilent",
        connector="pmod",
        provides=frozenset({"i2c-adc", "adc-12bit"}),
        power_draw_mw=180,
    ),
    CardSpec(
        name="pmod-rs485",
        vendor="digilent",
        connector="pmod",
        provides=frozenset({"rs485"}),
        power_draw_mw=220,
    ),
    CardSpec(
        name="sfp-1g-copper",
        vendor="finisar",
        connector="sfp-cage",
        provides=frozenset({"ethernet-1g"}),
        power_draw_mw=1100,
        transceivers_used=1,
    ),
)

CARDS: CatalogIndex[CardSpec] = CatalogIndex("card", _CATALOG, key=lambda spec: spec.name)


def lookup_card(name: str) -> CardSpec | None:
    return CARDS.lookup(name)


def card(name: str) -> CardSpec:
    return CARDS.require(name)


def cards_providing(capability: str) -> tuple[CardSpec, ...]:
    """Every card that brings a given capability, cheapest on power first."""
    wanted = slug(capability)
    hits = CARDS.where(lambda spec: wanted in spec.provides)
    return tuple(sorted(hits, key=lambda spec: (spec.power_draw_mw, spec.name)))


def all_cards() -> tuple[CardSpec, ...]:
    return CARDS.all()
