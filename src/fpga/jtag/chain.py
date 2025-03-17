"""The chain as a description: which devices, in which order, and how wide.

Order is the thing to get right and it is easy to state wrongly. The devices
are listed the way the vendor tools list them and the way the schematic reads,
from the connector's TDI pin through each part to TDO. Bits that come back
arrive from the device nearest TDO first, so a scan of the whole chain reads in
the reverse of the listed order. Every helper here says which of the two it
means in its name, because the one time it was implicit the boundary scan came
out mirrored and nobody could see why.

A position holds an instruction register width whether or not the bench knows
what the device is. An unknown part on the chain still has to be shifted past,
and the width is the only thing needed to do it.
"""

from __future__ import annotations

from dataclasses import dataclass

from fpga.catalog.devices import lookup_device
from fpga.errors import ConfigurationError, NotFound, ValidationError
from fpga.ids import slug

__all__ = [
    "ScanChain",
    "TapPosition",
]


@dataclass(frozen=True, slots=True)
class TapPosition:
    """One device on the chain, or one thing that has to be shifted past."""

    label: str
    ir_length: int
    device: str | None = None
    expected_idcode: int | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "label", slug(self.label))
        if not 2 <= self.ir_length <= 32:
            raise ValidationError(
                "an instruction register is between two and thirty two bits",
                field="ir_length",
                given=self.ir_length,
            )
        if self.expected_idcode is not None and not 0 <= self.expected_idcode <= 0xFFFFFFFF:
            raise ValidationError("idcode does not fit in a word", field="expected_idcode")

    @classmethod
    def for_device(cls, label: str, device: str) -> TapPosition:
        """A position whose width and idcode come from the catalog."""
        spec = lookup_device(device)
        if spec is None:
            raise NotFound("device", device.strip().lower())
        return cls(
            label=label,
            ir_length=spec.ir_length,
            device=spec.device,
            expected_idcode=spec.idcode,
        )

    @property
    def known(self) -> bool:
        return self.device is not None


@dataclass(frozen=True, slots=True)
class ScanChain:
    """Every device between TDI and TDO, listed in that order."""

    taps: tuple[TapPosition, ...]

    def __post_init__(self) -> None:
        if not self.taps:
            raise ConfigurationError("a chain has at least one device")
        labels = [tap.label for tap in self.taps]
        if len(set(labels)) != len(labels):
            raise ConfigurationError("two positions share a label", labels=labels)

    @classmethod
    def of_devices(cls, *devices: str) -> ScanChain:
        """A chain of catalogued parts, labelled u1, u2 and so on in TDI order."""
        return cls(
            tuple(
                TapPosition.for_device(f"u{number}", device)
                for number, device in enumerate(devices, start=1)
            )
        )

    def __len__(self) -> int:
        return len(self.taps)

    @property
    def ir_length(self) -> int:
        """Total instruction register width of the whole chain."""
        return sum(tap.ir_length for tap in self.taps)

    def index_of(self, label: str) -> int:
        wanted = slug(label)
        for index, tap in enumerate(self.taps):
            if tap.label == wanted:
                return index
        raise NotFound("tap", wanted)

    def at(self, index: int) -> TapPosition:
        if not 0 <= index < len(self.taps):
            raise ValidationError("no position at that index", field="index", given=index)
        return self.taps[index]

    def tap(self, label: str) -> TapPosition:
        return self.taps[self.index_of(label)]

    def devices_before_tdo(self, index: int) -> int:
        """How many devices sit between this one and TDO.

        Their bits come back first, so this is the number of bypass bits to
        drop off the front of a data register scan.
        """
        self.at(index)
        return len(self.taps) - index - 1

    def devices_after_tdo(self, index: int) -> int:
        """How many sit on the TDI side, whose bits arrive after this one's."""
        self.at(index)
        return index

    def ir_bits_before(self, index: int) -> int:
        """Instruction bits belonging to devices nearer TDO than this one."""
        self.at(index)
        return sum(tap.ir_length for tap in self.taps[index + 1 :])

    def ir_bits_after(self, index: int) -> int:
        self.at(index)
        return sum(tap.ir_length for tap in self.taps[:index])

    def in_tdo_order(self) -> tuple[TapPosition, ...]:
        """The chain as the bits come back, nearest TDO first."""
        return tuple(reversed(self.taps))

    def labels(self) -> tuple[str, ...]:
        return tuple(tap.label for tap in self.taps)

    def describe(self) -> str:
        """One line for a log, in TDI order with widths."""
        parts = [f"{tap.label}:{tap.device or 'unknown'}/ir{tap.ir_length}" for tap in self.taps]
        return " -> ".join(parts)
