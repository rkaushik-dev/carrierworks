"""One board to and from the json shape the bench file uses.

Kept apart from the loader because the loader is about the document and this is
about a board, and because the http layer serialises a board with exactly these
fields. Two writers of the same shape is how they drift.

Everything optional is omitted rather than written as null. A bench file that
somebody edits by hand should not be full of nulls for the cables that are not
plugged in today.
"""

from __future__ import annotations

from typing import Any

from fpga.catalog.partnumber import parse_part_number
from fpga.errors import ConfigurationError
from fpga.ids import AssetTag
from fpga.inventory.boards import Board, BoardSlot
from fpga.jtag.adapters import AdapterUnit
from fpga.locations import BenchLocation, PduChannel

__all__ = [
    "board_from_json",
    "board_to_json",
]

_REQUIRED = ("asset", "model", "part", "flash", "location")


def board_from_json(entry: dict[str, Any]) -> Board:
    """One board out of its json object."""
    missing = [field for field in _REQUIRED if not entry.get(field)]
    if missing:
        raise ConfigurationError(
            "a board entry is missing fields",
            board=str(entry.get("asset", "unnamed")),
            missing=", ".join(missing),
        )
    slots = tuple(
        BoardSlot(
            name=str(slot["name"]),
            connector=str(slot["connector"]),
            fitted=None if slot.get("fitted") in (None, "") else str(slot["fitted"]),
        )
        for slot in entry.get("slots", [])
    )
    adapter = None
    if entry.get("adapter"):
        raw = entry["adapter"]
        adapter = AdapterUnit(
            serial=str(raw["serial"]),
            model=str(raw["model"]),
            port=str(raw.get("port", "")),
        )
    pdu = None if not entry.get("pdu") else PduChannel.parse(str(entry["pdu"]))
    return Board(
        asset=AssetTag.parse(str(entry["asset"])),
        model=str(entry["model"]),
        part=parse_part_number(str(entry["part"])),
        flash=str(entry["flash"]),
        location=BenchLocation.parse(str(entry["location"])),
        revision=str(entry.get("revision", "a")),
        slots=slots,
        adapter=adapter,
        pdu=pdu,
        vadj_millivolts=int(entry.get("vadj_millivolts", 1800)),
        header_voltage_mv=int(entry.get("header_voltage_mv", 1800)),
        max_tck_hz=int(entry.get("max_tck_hz", 30_000_000)),
        notes=str(entry.get("notes", "")),
    )


def board_to_json(board: Board) -> dict[str, Any]:
    """The json object for a board, with nothing empty written out."""
    entry: dict[str, Any] = {
        "asset": board.tag,
        "model": board.model,
        "revision": board.revision,
        "part": board.part.ordering_code(),
        "flash": board.flash,
        "location": str(board.location),
        "vadj_millivolts": board.vadj_millivolts,
        "header_voltage_mv": board.header_voltage_mv,
        "max_tck_hz": board.max_tck_hz,
    }
    if board.slots:
        entry["slots"] = [
            {
                "name": slot.name,
                "connector": slot.connector,
                **({} if slot.fitted is None else {"fitted": slot.fitted}),
            }
            for slot in board.slots
        ]
    if board.adapter is not None:
        adapter: dict[str, Any] = {
            "serial": board.adapter.serial,
            "model": board.adapter.model,
        }
        if board.adapter.port:
            adapter["port"] = board.adapter.port
        entry["adapter"] = adapter
    if board.pdu is not None:
        entry["pdu"] = str(board.pdu)
    if board.notes:
        entry["notes"] = board.notes
    return entry
