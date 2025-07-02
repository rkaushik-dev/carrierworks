"""One board, as the bench knows it.

A board is a carrier with a device on it, a flash beside the device, some
connectors, a place in a rack, an outlet feeding it and usually a cable hanging
off it. Every one of those is a fact somebody has to keep right, and the ones
that interact are the ones that go wrong.

Three interactions are checked here and all three have bitten this lab.

A card asks the carrier for an adjustable rail voltage, and a carrier that is
strapped to a different one will bring the card up in a state where it
enumerates and then behaves oddly under load. Nothing about that looks like a
wiring fault when it happens.

Cards use serial transceivers, and the device has a fixed number of them. Two
four lane cards on an Artix with four is a design nobody can route, and it is
better to find that out when somebody writes the board down than when a build
fails after forty minutes of place and route.

And the cable has to be able to drive the header. An adapter that only does
3.3 volts on a carrier whose header is at 1.8 will read a chain of garbage,
which looks exactly like a broken board.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from fpga.catalog.cards import CardSpec, card
from fpga.catalog.devices import DeviceSpec, device_for
from fpga.catalog.flashparts import FlashSpec, flash_part
from fpga.catalog.partnumber import PartNumber, parse_part_number
from fpga.errors import ConfigurationError, NotFound, ValidationError
from fpga.ids import AssetTag, slug
from fpga.jtag.adapters import AdapterUnit
from fpga.locations import BenchLocation, PduChannel

__all__ = [
    "Board",
    "BoardSlot",
]


@dataclass(frozen=True, slots=True)
class BoardSlot:
    """One connector on a carrier, with whatever is in it."""

    name: str
    connector: str
    fitted: str | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "name", slug(self.name))
        object.__setattr__(self, "connector", slug(self.connector))
        if self.fitted is not None:
            object.__setattr__(self, "fitted", slug(self.fitted))
            spec = card(self.fitted)
            if not spec.fits(self.connector):
                raise ConfigurationError(
                    "that card does not fit that connector",
                    slot=self.name,
                    connector=self.connector,
                    fitted=self.fitted,
                )

    @property
    def empty(self) -> bool:
        return self.fitted is None

    def card_spec(self) -> CardSpec | None:
        return None if self.fitted is None else card(self.fitted)

    def with_card(self, name: str | None) -> BoardSlot:
        return BoardSlot(name=self.name, connector=self.connector, fitted=name)


@dataclass(frozen=True, slots=True)
class Board:
    """A carrier in a rack, and everything true about it."""

    asset: AssetTag
    model: str
    part: PartNumber
    flash: str
    location: BenchLocation
    revision: str = "a"
    slots: tuple[BoardSlot, ...] = ()
    adapter: AdapterUnit | None = None
    pdu: PduChannel | None = None
    vadj_millivolts: int = 1800
    header_voltage_mv: int = 1800
    max_tck_hz: int = 30_000_000
    notes: str = ""

    def __post_init__(self) -> None:
        object.__setattr__(self, "model", slug(self.model))
        object.__setattr__(self, "revision", slug(self.revision))
        device = device_for(self.part)
        flash_part(self.flash)
        names = [slot.name for slot in self.slots]
        if len(set(names)) != len(names):
            raise ConfigurationError("two slots share a name", board=str(self.asset), slots=names)
        used = sum(spec.transceivers_used for spec in self.fitted_cards())
        if used > device.transceivers:
            raise ConfigurationError(
                "the fitted cards want more transceivers than the device has",
                board=str(self.asset),
                wanted=used,
                have=device.transceivers,
            )
        for spec in self.fitted_cards():
            if spec.vadj_millivolts is not None and spec.vadj_millivolts != self.vadj_millivolts:
                raise ConfigurationError(
                    "a fitted card wants a different adjustable rail",
                    board=str(self.asset),
                    fitted=spec.name,
                    wants=spec.vadj_millivolts,
                    strapped=self.vadj_millivolts,
                )
        if self.adapter is not None and not self.adapter.spec.drives(self.header_voltage_mv):
            raise ConfigurationError(
                "that cable cannot drive this board's header",
                board=str(self.asset),
                adapter=self.adapter.model,
                header=self.header_voltage_mv,
            )
        if self.max_tck_hz <= 0:
            raise ValidationError("a board has a clock ceiling", field="max_tck_hz")

    @classmethod
    def build(
        cls,
        asset: str,
        model: str,
        part: str,
        flash: str,
        location: str,
        **rest: object,
    ) -> Board:
        """The form a config file uses, where everything arrives as a string."""
        return cls(
            asset=AssetTag.parse(asset),
            model=model,
            part=parse_part_number(part),
            flash=flash,
            location=BenchLocation.parse(location),
            **rest,  # type: ignore[arg-type]
        )

    @property
    def tag(self) -> str:
        return str(self.asset)

    def device(self) -> DeviceSpec:
        return device_for(self.part)

    def flash_spec(self) -> FlashSpec:
        return flash_part(self.flash)

    def slot(self, name: str) -> BoardSlot:
        wanted = slug(name)
        for slot in self.slots:
            if slot.name == wanted:
                return slot
        raise NotFound("slot", wanted)

    def fitted_cards(self) -> tuple[CardSpec, ...]:
        return tuple(spec for spec in (slot.card_spec() for slot in self.slots) if spec is not None)

    def empty_slots(self) -> tuple[BoardSlot, ...]:
        return tuple(slot for slot in self.slots if slot.empty)

    @property
    def transceivers_free(self) -> int:
        used = sum(spec.transceivers_used for spec in self.fitted_cards())
        return self.device().transceivers - used

    @property
    def card_power_mw(self) -> int:
        return sum(spec.power_draw_mw for spec in self.fitted_cards())

    def with_card(self, slot: str, name: str | None) -> Board:
        """The same board with a card fitted or removed, checked again.

        Fitting a card is a physical act somebody has already done, so this
        cannot refuse to record it. What it can do is refuse to record it
        wrongly, which is why the whole board is rebuilt rather than patched.
        """
        target = self.slot(slot)
        slots = tuple(
            entry.with_card(name) if entry.name == target.name else entry for entry in self.slots
        )
        return Board(
            asset=self.asset,
            model=self.model,
            part=self.part,
            flash=self.flash,
            location=self.location,
            revision=self.revision,
            slots=slots,
            adapter=self.adapter,
            pdu=self.pdu,
            vadj_millivolts=self.vadj_millivolts,
            header_voltage_mv=self.header_voltage_mv,
            max_tck_hz=self.max_tck_hz,
            notes=self.notes,
        )

    def describe(self) -> str:
        fitted = ", ".join(spec.name for spec in self.fitted_cards()) or "bare"
        return (
            f"{self.tag} {self.model} rev {self.revision} {self.part} at {self.location} [{fitted}]"
        )


@dataclass(frozen=True, slots=True)
class BoardSummary:
    """The short form for a listing, built once rather than in the template."""

    tag: str
    model: str
    device: str
    location: str
    cards: tuple[str, ...] = field(default_factory=tuple)

    @classmethod
    def of(cls, board: Board) -> BoardSummary:
        return cls(
            tag=board.tag,
            model=board.model,
            device=board.device().device,
            location=str(board.location),
            cards=tuple(spec.name for spec in board.fitted_cards()),
        )
