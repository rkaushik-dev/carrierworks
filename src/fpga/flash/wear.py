"""Counting erases, because flash wears out and boards get reused for years.

A configuration flash is rated for a hundred thousand erase cycles per sector,
which sounds like a number nobody in a lab will ever reach. A board on the
nightly regression gets programmed twice a night, five nights a week, which is
five hundred a year, and the boards in this lab are eight years old. That is
still nowhere near the rating. The reason to count anyway is that the count is
not spread evenly: every image starts at address zero, so sector zero has taken
every erase any image on that board has ever caused, while the sectors at the
top of the part have taken none.

So the ledger is per sector and per board, it is written from the plan rather
than from an intention, and it can say which sectors are getting close. A part
that starts failing verification after a program is usually a worn sector, and
knowing that saves somebody from replacing a perfectly good carrier.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from fpga.catalog.flashparts import FlashSpec
from fpga.errors import ValidationError
from fpga.flash.plan import FlashPlan
from fpga.ids import AssetTag
from fpga.records import board_key

__all__ = [
    "SectorWear",
    "WearLedger",
]

# Past this fraction of the rated cycles a sector is worth watching.
_WARN_FRACTION = 0.8


@dataclass(frozen=True, slots=True)
class SectorWear:
    """How much of one sector's life has been used."""

    sector: int
    erases: int
    rated: int

    @property
    def fraction(self) -> float:
        return self.erases / self.rated if self.rated else 0.0

    @property
    def remaining(self) -> int:
        return max(0, self.rated - self.erases)

    @property
    def worn(self) -> bool:
        return self.fraction >= _WARN_FRACTION

    def describe(self) -> str:
        return f"sector {self.sector}: {self.erases} of {self.rated} erases"


class WearLedger:
    """Erase counts for every board the bench has programmed.

    Kept as one json file because it is small, it is written once per
    programming job, and an operator being able to read it with cat has been
    worth more than any query it might have supported.
    """

    def __init__(self, path: Path | str) -> None:
        self.path = Path(path)
        self._counts: dict[str, dict[int, int]] = {}
        self._load()

    def _load(self) -> None:
        if not self.path.is_file():
            return
        raw = json.loads(self.path.read_text(encoding="utf-8"))
        self._counts = {
            asset: {int(sector): int(count) for sector, count in sectors.items()}
            for asset, sectors in raw.items()
        }

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        serialisable = {
            asset: {str(sector): count for sector, count in sorted(sectors.items())}
            for asset, sectors in sorted(self._counts.items())
        }
        self.path.write_text(json.dumps(serialisable, indent=2), encoding="utf-8")

    def _key(self, asset: AssetTag | str) -> str:
        return board_key(str(asset))

    def record(self, asset: AssetTag | str, plan: FlashPlan) -> int:
        """Count the erases a plan will cause, and return how many that was.

        Called with the plan rather than after the run, because a plan that
        was half executed still erased the sectors it got to, and undercounting
        wear is the failure that matters here.
        """
        key = self._key(asset)
        sectors = self._counts.setdefault(key, {})
        touched = 0
        for step in plan.steps:
            if not step.kind.startswith("erase"):
                continue
            first = plan.part.sector_of(step.address)
            last = plan.part.sector_of(step.address + step.length - 1)
            for sector in range(first, last + 1):
                sectors[sector] = sectors.get(sector, 0) + 1
                touched += 1
        return touched

    def restore(self, asset: AssetTag | str, counts: dict[int, int]) -> None:
        """Load counts from somewhere else, replacing whatever is held.

        The lab kept this in a spreadsheet for years and the numbers in it are
        real history worth carrying over rather than starting from zero.
        """
        for sector, count in counts.items():
            if sector < 0 or count < 0:
                raise ValidationError("erase counts are not negative", field="counts")
        self._counts[self._key(asset)] = dict(counts)

    def erases(self, asset: AssetTag | str, sector: int) -> int:
        if sector < 0:
            raise ValidationError("sectors are counted from zero", field="sector", given=sector)
        return self._counts.get(self._key(asset), {}).get(sector, 0)

    def total(self, asset: AssetTag | str) -> int:
        return sum(self._counts.get(self._key(asset), {}).values())

    def boards(self) -> tuple[str, ...]:
        return tuple(sorted(self._counts))

    def wear(self, asset: AssetTag | str, part: FlashSpec) -> tuple[SectorWear, ...]:
        """Every sector this board has erased, most worn first."""
        sectors = self._counts.get(self._key(asset), {})
        found = [
            SectorWear(sector=sector, erases=count, rated=part.erase_cycles)
            for sector, count in sectors.items()
        ]
        return tuple(sorted(found, key=lambda entry: (-entry.erases, entry.sector)))

    def hottest(
        self, asset: AssetTag | str, part: FlashSpec, limit: int = 5
    ) -> tuple[SectorWear, ...]:
        if limit < 1:
            raise ValidationError("ask for at least one sector", field="limit", given=limit)
        return self.wear(asset, part)[:limit]

    def worn_sectors(self, asset: AssetTag | str, part: FlashSpec) -> tuple[SectorWear, ...]:
        return tuple(entry for entry in self.wear(asset, part) if entry.worn)

    def is_worn(self, asset: AssetTag | str, part: FlashSpec) -> bool:
        return bool(self.worn_sectors(asset, part))
