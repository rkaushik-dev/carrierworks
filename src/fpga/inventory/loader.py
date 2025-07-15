"""Reading the bench out of a file, and writing it back.

The inventory is kept in version control as one json document, because the
lab's changes to it are the same kind of thing as a change to the code: a
person did something physical, and everyone else needs to know. A pull request
with "moved bx-14 to rack 4 and put the sfp card on it" is exactly the record
that was missing when this was a spreadsheet.

Errors name the board and the field. A file with forty boards in it and a
message saying a value is wrong somewhere is not much better than no message,
so every failure carries the tag it was reading when it gave up.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from fpga.errors import ConfigurationError
from fpga.inventory.board_json import board_from_json, board_to_json
from fpga.inventory.book import BoardBook

__all__ = [
    "dump_bench",
    "load_bench",
    "read_bench",
]


def read_bench(text: str, *, when: datetime | None = None) -> BoardBook:
    """Build a board book out of a json document."""
    try:
        raw = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ConfigurationError("the bench file is not json", detail=str(exc)) from exc
    if not isinstance(raw, dict):
        raise ConfigurationError("the bench file holds an object")
    boards = raw.get("boards")
    if not isinstance(boards, list):
        raise ConfigurationError("the bench file needs a list of boards")
    moment = when or datetime.now(UTC)
    book = BoardBook(when=moment)
    for index, entry in enumerate(boards):
        if not isinstance(entry, dict):
            raise ConfigurationError("a board entry is an object", position=index)
        tag = str(entry.get("asset", f"entry {index}"))
        try:
            board = board_from_json(entry)
        except ConfigurationError:
            raise
        except Exception as exc:
            raise ConfigurationError(
                "that board could not be read: " + str(exc),
                board=tag,
                position=index,
            ) from exc
        book.add(
            board,
            when=moment,
            state=str(entry.get("state", "available")),
            reason=str(entry.get("reason", "")),
        )
    return book


def load_bench(path: str | Path, *, when: datetime | None = None) -> BoardBook:
    location = Path(path)
    if not location.is_file():
        raise ConfigurationError("no bench file there", path=str(location))
    return read_bench(location.read_text(encoding="utf-8"), when=when)


def dump_bench(book: BoardBook, *, site: str = "lab-a") -> str:
    """The book back out as json, in the same shape it was read from."""
    payload: dict[str, Any] = {
        "site": site,
        "boards": [
            {**board_to_json(board), "state": book.state(board.tag).state} for board in book.all()
        ],
    }
    return json.dumps(payload, indent=2, sort_keys=True) + "\n"
