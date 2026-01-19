"""The board routes: what is on the bench and what state it is in."""

from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends, Query

from fpga.access import ApiKey
from fpga.api.deps import now, unit_of_work
from fpga.api.schemas import BoardIn, BoardOut, StateIn
from fpga.api.security import needs
from fpga.catalog.partnumber import parse_part_number
from fpga.ids import AssetTag
from fpga.inventory.boards import Board, BoardSlot
from fpga.jtag.adapters import AdapterUnit
from fpga.locations import BenchLocation, PduChannel
from fpga.persistence.unit import UnitOfWork
from fpga.services import inventory

router = APIRouter(prefix="/boards", tags=["boards"])


def _to_board(incoming: BoardIn) -> Board:
    """One board out of the shape the wire uses."""
    adapter = None
    if incoming.adapter_serial and incoming.adapter_model:
        adapter = AdapterUnit(serial=incoming.adapter_serial, model=incoming.adapter_model)
    return Board(
        asset=AssetTag.parse(incoming.asset),
        model=incoming.model,
        part=parse_part_number(incoming.part),
        flash=incoming.flash,
        location=BenchLocation.parse(incoming.location),
        revision=incoming.revision,
        slots=tuple(
            BoardSlot(name=slot.name, connector=slot.connector, fitted=slot.fitted)
            for slot in incoming.slots
        ),
        adapter=adapter,
        pdu=None if incoming.pdu is None else PduChannel.parse(incoming.pdu),
        vadj_millivolts=incoming.vadj_millivolts,
        header_voltage_mv=incoming.header_voltage_mv,
        max_tck_hz=incoming.max_tck_hz,
        notes=incoming.notes,
    )


@router.get("", response_model=list[BoardOut])
def list_boards(
    unit: UnitOfWork = Depends(unit_of_work),
    available: bool = Query(default=False, description="only boards nobody is using"),
    rack: str | None = Query(default=None, description="one rack rather than the whole bench"),
    _: ApiKey = Depends(needs("reader")),
) -> list[BoardOut]:
    """Every board, or the ones a filter asks for."""
    if rack is not None:
        boards = unit.boards.in_rack(rack)
    else:
        boards = inventory.list_boards(unit, only_available=available)
    return [BoardOut.of(board, unit.boards.state(board.tag)) for board in boards]


@router.get("/{tag}", response_model=BoardOut)
def read_board(
    tag: str,
    unit: UnitOfWork = Depends(unit_of_work),
    _: ApiKey = Depends(needs("reader")),
) -> BoardOut:
    """One board, with the state it is in."""
    return BoardOut.of(unit.boards.get(tag), unit.boards.state(tag))


@router.post("", response_model=BoardOut, status_code=201)
def add_board(
    incoming: BoardIn,
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    _: ApiKey = Depends(needs("admin")),
) -> BoardOut:
    """Put a board on the bench."""
    board = inventory.add_board(unit, _to_board(incoming), moment)
    unit.flush()
    return BoardOut.of(board, unit.boards.state(board.tag))


@router.post("/{tag}/state", response_model=BoardOut)
def set_state(
    tag: str,
    change: StateIn,
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    _: ApiKey = Depends(needs("operator")),
) -> BoardOut:
    """Move a board's state, through the rules that say which moves exist."""
    if change.state == "faulted":
        inventory.fault_board(
            unit,
            tag,
            moment,
            change.reason or "faulted by hand",
            actor=change.actor or "api",
        )
    else:
        inventory.move_state(
            unit,
            tag,
            change.state,
            moment,
            reason=change.reason,
            actor=change.actor or "api",
        )
    return BoardOut.of(unit.boards.get(tag), unit.boards.state(tag))


@router.put("/{tag}/slots/{slot}", response_model=BoardOut)
def fit_card(
    tag: str,
    slot: str,
    card: str | None = Query(default=None, description="the card, or nothing to remove one"),
    unit: UnitOfWork = Depends(unit_of_work),
    _: ApiKey = Depends(needs("admin")),
) -> BoardOut:
    """Record a card being fitted to or taken out of a slot."""
    board = inventory.fit_card(unit, tag, slot, card)
    unit.flush()
    return BoardOut.of(board, unit.boards.state(tag))


# A return annotation of None is a response model of NoneType as far as the
# framework is concerned, and it refuses to pair one with a 204. Saying there
# is no model is what actually means no body.
@router.delete("/{tag}", status_code=204, response_model=None)
def remove_board(
    tag: str,
    unit: UnitOfWork = Depends(unit_of_work),
    _: ApiKey = Depends(needs("admin")),
) -> None:
    """Take a board off the bench entirely."""
    unit.boards.remove(tag)
