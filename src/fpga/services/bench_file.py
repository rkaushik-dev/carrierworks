"""Reading a bench description into the database, and writing it back out.

Boards arrive as a json file. Somebody racks four carriers, writes down what
went where, and the file is what the bench is told. Doing that by hand through
the board commands is four long command lines with an asset tag in each, which
is exactly the shape of thing that gets one character wrong.

Importing is deliberately not a merge. A board already on the bench is left
alone unless the caller says to replace it, because the common mistake is
running the file twice, and the expensive mistake is having the second run
quietly overwrite a location somebody corrected by hand last week.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from fpga.errors import NotFound
from fpga.inventory.board_json import board_to_json
from fpga.inventory.loader import read_bench
from fpga.persistence.unit import UnitOfWork
from fpga.services import inventory

__all__ = [
    "ImportOutcome",
    "export_bench",
    "import_bench",
]


@dataclass(frozen=True, slots=True)
class ImportOutcome:
    """What a bench file did to the database."""

    added: tuple[str, ...] = ()
    replaced: tuple[str, ...] = ()
    skipped: tuple[str, ...] = ()

    @property
    def changed(self) -> int:
        return len(self.added) + len(self.replaced)

    def describe(self) -> str:
        parts = []
        if self.added:
            parts.append(f"added {', '.join(self.added)}")
        if self.replaced:
            parts.append(f"replaced {', '.join(self.replaced)}")
        if self.skipped:
            parts.append(f"left alone {', '.join(self.skipped)}")
        return "; ".join(parts) or "nothing in that file"


def import_bench(
    unit: UnitOfWork,
    text: str,
    now: datetime,
    *,
    replace: bool = False,
) -> ImportOutcome:
    """Put every board in a bench file onto the bench."""
    book = read_bench(text, when=now)
    added: list[str] = []
    replaced: list[str] = []
    skipped: list[str] = []
    for board in book.all():
        try:
            unit.boards.get(board.tag)
        except NotFound:
            inventory.add_board(unit, board, now, state=book.state(board.tag).state)
            added.append(board.tag)
            continue
        if not replace:
            skipped.append(board.tag)
            continue
        unit.boards.replace(board)
        replaced.append(board.tag)
    return ImportOutcome(
        added=tuple(added),
        replaced=tuple(replaced),
        skipped=tuple(skipped),
    )


def export_bench(unit: UnitOfWork, *, site: str = "lab-a") -> dict[str, Any]:
    """Every board on the bench, in the shape the loader reads.

    The state goes out with each board, which the loader accepts and which
    means a file written here and read back somewhere else describes the same
    bench rather than a bench where everything is available.
    """
    return {
        "site": site,
        "boards": [
            {**board_to_json(board), "state": unit.boards.state(board.tag).state}
            for board in unit.boards.all()
        ],
    }


def read_file(path: str | Path) -> str:
    """The text of a bench file, with a bench error when there is none."""
    location = Path(path)
    if not location.is_file():
        raise NotFound("bench file", str(location))
    return location.read_text(encoding="utf-8")
