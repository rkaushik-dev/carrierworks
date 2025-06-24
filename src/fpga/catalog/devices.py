"""What is actually inside each part the lab keeps on the shelf.

Two kinds of number live here and they are used at opposite ends of the bench.
The resource counts are what a utilization report gets compared against, so a
design that will not fit is caught before anyone books a board for it. The
idcode and the instruction register length are what the scan chain code needs
to talk to the device at all, and getting either wrong means the chain reads
back as garbage rather than as an error.

Configuration bit counts are the uncompressed bitstream length for the part.
The flash planner uses them to work out whether a golden and an update image
will both sit in the device the board actually carries.
"""

from __future__ import annotations

from dataclasses import dataclass

from fpga.catalog.index import CatalogIndex
from fpga.catalog.partnumber import PartNumber

__all__ = [
    "DEVICES",
    "DeviceSpec",
    "all_devices",
    "device_for",
    "device_for_idcode",
    "devices_in_family",
    "lookup_device",
]

# The revision nibble at the top of an idcode changes with the silicon stepping
# and tells us nothing about which part is on the board.
IDCODE_MASK = 0x0FFFFFFF


@dataclass(frozen=True, slots=True)
class DeviceSpec:
    """One silicon device, as the bench needs to know it."""

    device: str
    family: str
    idcode: int
    ir_length: int
    luts: int
    flip_flops: int
    block_ram_36k: int
    dsp_slices: int
    config_bits: int
    transceivers: int = 0

    @property
    def config_bytes(self) -> int:
        """Uncompressed configuration length, rounded up to whole bytes."""
        return (self.config_bits + 7) // 8

    @property
    def block_ram_bits(self) -> int:
        return self.block_ram_36k * 36 * 1024

    def matches_idcode(self, idcode: int) -> bool:
        return idcode & IDCODE_MASK == self.idcode & IDCODE_MASK

    def holds(self, luts: int, flip_flops: int, block_ram_36k: int, dsp_slices: int) -> bool:
        """Whether a design of this size fits, ignoring routing.

        Routing is the reason a design that fits by these numbers still fails
        to place, so this is a fast no rather than a slow yes.
        """
        return (
            luts <= self.luts
            and flip_flops <= self.flip_flops
            and block_ram_36k <= self.block_ram_36k
            and dsp_slices <= self.dsp_slices
        )


_CATALOG: tuple[DeviceSpec, ...] = (
    DeviceSpec(
        device="xc7s25",
        family="spartan7",
        idcode=0x037C4093,
        ir_length=6,
        luts=14600,
        flip_flops=29200,
        block_ram_36k=45,
        dsp_slices=80,
        config_bits=9_935_552,
    ),
    DeviceSpec(
        device="xc7s50",
        family="spartan7",
        idcode=0x0362F093,
        ir_length=6,
        luts=32600,
        flip_flops=65200,
        block_ram_36k=75,
        dsp_slices=120,
        config_bits=17_536_096,
    ),
    DeviceSpec(
        device="xc7a35t",
        family="artix7",
        idcode=0x0362D093,
        ir_length=6,
        luts=20800,
        flip_flops=41600,
        block_ram_36k=50,
        dsp_slices=90,
        config_bits=17_536_096,
        transceivers=4,
    ),
    DeviceSpec(
        device="xc7a50t",
        family="artix7",
        idcode=0x0362C093,
        ir_length=6,
        luts=32600,
        flip_flops=65200,
        block_ram_36k=75,
        dsp_slices=120,
        config_bits=17_536_096,
        transceivers=4,
    ),
    DeviceSpec(
        device="xc7a100t",
        family="artix7",
        idcode=0x03631093,
        ir_length=6,
        luts=63400,
        flip_flops=126800,
        block_ram_36k=135,
        dsp_slices=240,
        config_bits=30_606_304,
        transceivers=4,
    ),
    DeviceSpec(
        device="xc7a200t",
        family="artix7",
        idcode=0x03636093,
        ir_length=6,
        luts=134600,
        flip_flops=269200,
        block_ram_36k=365,
        dsp_slices=740,
        config_bits=77_845_216,
        transceivers=8,
    ),
    DeviceSpec(
        device="xc7k70t",
        family="kintex7",
        idcode=0x03647093,
        ir_length=6,
        luts=41000,
        flip_flops=82000,
        block_ram_36k=135,
        dsp_slices=240,
        config_bits=24_090_592,
        transceivers=8,
    ),
    DeviceSpec(
        device="xc7k325t",
        family="kintex7",
        idcode=0x03651093,
        ir_length=6,
        luts=203800,
        flip_flops=407600,
        block_ram_36k=445,
        dsp_slices=840,
        config_bits=91_548_896,
        transceivers=16,
    ),
    DeviceSpec(
        device="xc7k410t",
        family="kintex7",
        idcode=0x03656093,
        ir_length=6,
        luts=254200,
        flip_flops=508400,
        block_ram_36k=795,
        dsp_slices=1540,
        config_bits=127_023_328,
        transceivers=16,
    ),
    DeviceSpec(
        device="xc7z020",
        family="zynq7000",
        idcode=0x03727093,
        ir_length=6,
        luts=53200,
        flip_flops=106400,
        block_ram_36k=140,
        dsp_slices=220,
        config_bits=32_364_512,
        transceivers=0,
    ),
    DeviceSpec(
        device="xcku040",
        family="kintex_ultrascale",
        idcode=0x03822093,
        ir_length=6,
        luts=242400,
        flip_flops=484800,
        block_ram_36k=600,
        dsp_slices=1920,
        config_bits=126_411_296,
        transceivers=20,
    ),
)

DEVICES: CatalogIndex[DeviceSpec] = CatalogIndex(
    "device",
    _CATALOG,
    key=lambda spec: spec.device,
    secondary={"idcode": lambda spec: spec.idcode & IDCODE_MASK},
)


def lookup_device(device: str) -> DeviceSpec | None:
    """The device with this code, or nothing if the lab does not stock it."""
    return DEVICES.lookup(device)


def device_for(part: PartNumber | str) -> DeviceSpec:
    """The device behind a part number or a bare device code."""
    return DEVICES.require(part.device if isinstance(part, PartNumber) else part)


def device_for_idcode(idcode: int) -> DeviceSpec:
    """The device that answered a scan, revision nibble ignored."""
    return DEVICES.require_by("idcode", idcode & IDCODE_MASK, shown=f"0x{idcode:08x}")


def devices_in_family(family: str) -> tuple[DeviceSpec, ...]:
    wanted = family.strip().lower()
    return DEVICES.where(lambda spec: spec.family == wanted)


def all_devices() -> tuple[DeviceSpec, ...]:
    return DEVICES.all()
