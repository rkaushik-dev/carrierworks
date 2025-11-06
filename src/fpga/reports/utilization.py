"""Reading how much of a device a design used.

The report is a set of tables, one per site type family, and the bench wants
four rows out of all of them: logic, registers, block memory and the multiplier
slices. Those four are what decide whether a design will fit a smaller part,
which is the question the scheduler asks when somebody books the only Kintex to
run something an Artix would hold.

Row names have changed between tool versions more than once, so each of the
four is matched against a small set of names rather than one. What has never
changed is the shape: a name, a used count, a fixed count nobody reads, an
available count and a percentage the parser recomputes rather than trusts.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from fpga.catalog.devices import DeviceSpec
from fpga.errors import ValidationError
from fpga.ids import design_name

__all__ = [
    "SiteUsage",
    "Utilization",
    "parse_utilization_report",
]

_ROW = re.compile(
    r"^\|\s*(?P<name>[A-Za-z][\w\s\.\-\(\)/]*?)\s*\|"
    r"\s*(?P<used>\d+)\s*\|"
    r"\s*(?P<fixed>\d+)\s*\|"
    r"(?:\s*(?P<prohibited>\d+)\s*\|)?"
    r"\s*(?P<available>\d+)\s*\|"
    r"\s*(?P<percent>\d+(?:\.\d+)?)\s*\|\s*$"
)
_DESIGN = re.compile(r"^\s*\|?\s*Design\s*:\s*(?P<design>[\w\-/\.]+)", re.IGNORECASE)

# The names each tool version has used for the rows the bench reads.
_ALIASES: dict[str, tuple[str, ...]] = {
    "luts": ("slice luts", "clb luts", "total luts", "slice luts*"),
    "registers": ("slice registers", "clb registers", "register as flip flop"),
    "block_ram": ("block ram tile", "block ram tile*", "ramb36/fifo"),
    "dsp": ("dsps", "dsp48e1 only", "dsp slices"),
}


@dataclass(frozen=True, slots=True)
class SiteUsage:
    """One row of a utilization table."""

    name: str
    used: int
    available: int

    def __post_init__(self) -> None:
        if self.used < 0 or self.available < 0:
            raise ValidationError("counts are not negative", field="usage", name=self.name)
        if self.used > self.available:
            raise ValidationError(
                "a design cannot use more sites than the part has",
                field="usage",
                name=self.name,
                used=self.used,
                available=self.available,
            )

    @property
    def percent(self) -> float:
        if not self.available:
            return 0.0
        return round(self.used * 100 / self.available, 2)

    def describe(self) -> str:
        return f"{self.name} {self.used}/{self.available} ({self.percent:.1f}%)"


@dataclass(frozen=True, slots=True)
class Utilization:
    """What a design used, in the four numbers the bench compares on."""

    rows: tuple[SiteUsage, ...]
    design: str = ""

    def __post_init__(self) -> None:
        if self.design:
            object.__setattr__(self, "design", design_name(self.design))

    def row(self, kind: str) -> SiteUsage | None:
        """The row for one of the four kinds, whatever the tool called it."""
        names = _ALIASES.get(kind)
        if names is None:
            raise ValidationError("no such utilization kind", field="kind", given=kind)
        for row in self.rows:
            if row.name.strip().lower() in names:
                return row
        return None

    def used(self, kind: str) -> int:
        row = self.row(kind)
        return 0 if row is None else row.used

    @property
    def luts(self) -> int:
        return self.used("luts")

    @property
    def registers(self) -> int:
        return self.used("registers")

    @property
    def block_ram(self) -> int:
        return self.used("block_ram")

    @property
    def dsp(self) -> int:
        return self.used("dsp")

    def busiest(self) -> SiteUsage | None:
        """The row nearest full, which is the one that will stop a design fitting."""
        if not self.rows:
            return None
        return max(self.rows, key=lambda row: (row.percent, row.name))

    def over(self, percent: float) -> tuple[SiteUsage, ...]:
        return tuple(row for row in self.rows if row.percent >= percent)

    def fits(self, device: DeviceSpec) -> bool:
        """Whether this design would fit another part, by the four numbers."""
        return device.holds(
            luts=self.luts,
            flip_flops=self.registers,
            block_ram_36k=self.block_ram,
            dsp_slices=self.dsp,
        )

    def describe(self) -> str:
        busiest = self.busiest()
        name = f"{self.design}: " if self.design else ""
        if busiest is None:
            return f"{name}nothing used"
        return f"{name}{self.luts} luts, {self.registers} registers, busiest {busiest.describe()}"


def parse_utilization_report(text: str) -> Utilization:
    """Read every table row in a report, whichever table it was in."""
    rows: list[SiteUsage] = []
    design = ""
    for line in text.splitlines():
        if not design:
            found = _DESIGN.match(line)
            if found:
                design = found.group("design")
        row = _ROW.match(line)
        if row is None:
            continue
        name = row.group("name").strip()
        if name.lower() in ("site type", "ref name"):
            continue
        rows.append(
            SiteUsage(
                name=name,
                used=int(row.group("used")),
                available=int(row.group("available")),
            )
        )
    if not rows:
        raise ValidationError("no utilization tables in that report", field="report")
    return Utilization(rows=tuple(rows), design=design)
