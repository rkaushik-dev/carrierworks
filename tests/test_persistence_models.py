from datetime import UTC, datetime

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from fpga.config import BenchSettings
from fpga.persistence.engine import build_engine, create_schema, session_factory, session_scope
from fpga.persistence.models import Base, BoardRow, BoardStateRow, SlotRow

NOW = datetime(2025, 11, 18, 10, 0, tzinfo=UTC)


@pytest.fixture()
def factory(tmp_path):
    settings = BenchSettings(database_url=f"sqlite+pysqlite:///{tmp_path / 'bench.db'}")
    engine = build_engine(settings)
    create_schema(engine)
    return session_factory(engine)


def board_row(tag="bx-14", rack="rack-2", shelf=4, slot=3, serial="210308AB1234", outlet=7):
    return BoardRow(
        tag=tag,
        model="rx-carrier-b",
        part="xc7k325t-2ffg900i",
        device="xc7k325t",
        flash="n25q128a13",
        rack=rack,
        shelf=shelf,
        slot=slot,
        adapter_serial=serial,
        adapter_model="jtag-hs2",
        pdu="pdu-a",
        outlet=outlet,
    )


def test_the_schema_holds_the_tables_the_bench_needs():
    names = set(Base.metadata.tables)
    assert {"boards", "board_slots", "board_states"} <= names


def test_a_board_round_trips(factory):
    with session_scope(factory) as session:
        session.add(board_row())
    with session_scope(factory) as session:
        found = session.get(BoardRow, "bx-14")
        assert found is not None
        assert found.device == "xc7k325t"
        assert found.revision == "a"
        assert found.max_tck_hz == 30_000_000


def test_slots_come_back_with_their_board(factory):
    with session_scope(factory) as session:
        board = board_row()
        board.slots.append(SlotRow(name="fmc0", connector="fmc-hpc", fitted="fmc-adc-4ch-14b"))
        board.slots.append(SlotRow(name="fmc1", connector="fmc-lpc"))
        session.add(board)
    with session_scope(factory) as session:
        found = session.get(BoardRow, "bx-14")
        assert [slot.name for slot in found.slots] == ["fmc0", "fmc1"]
        assert found.slots[1].fitted is None


def test_two_boards_cannot_share_a_slot_in_the_rack(factory):
    with session_scope(factory) as session:
        session.add(board_row())
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(board_row(tag="bx-15", serial="210308CD5678", outlet=9))


def test_two_boards_cannot_share_a_cable(factory):
    with session_scope(factory) as session:
        session.add(board_row())
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(board_row(tag="bx-15", shelf=5, outlet=9))


def test_two_boards_cannot_share_an_outlet(factory):
    with session_scope(factory) as session:
        session.add(board_row())
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(board_row(tag="bx-15", shelf=5, serial="210308CD5678"))


def test_a_shelf_outside_the_rack_is_refused(factory):
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(board_row(shelf=99))


def test_a_slot_outside_the_shelf_is_refused(factory):
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(board_row(slot=9))


def test_two_slots_on_one_board_cannot_share_a_name(factory):
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        board = board_row()
        board.slots.append(SlotRow(name="fmc0", connector="fmc-hpc"))
        board.slots.append(SlotRow(name="fmc0", connector="fmc-lpc"))
        session.add(board)


def test_a_state_hangs_off_its_board(factory):
    with session_scope(factory) as session:
        board = board_row()
        board.state = BoardStateRow(state="available", since=NOW, is_available=True)
        session.add(board)
    with session_scope(factory) as session:
        found = session.get(BoardRow, "bx-14")
        assert found.state is not None
        assert found.state.state == "available"
        assert found.state.is_available


def test_deleting_a_board_takes_its_slots_and_state_with_it(factory):
    with session_scope(factory) as session:
        board = board_row()
        board.slots.append(SlotRow(name="fmc0", connector="fmc-hpc"))
        board.state = BoardStateRow(state="available", since=NOW)
        session.add(board)
    with session_scope(factory) as session:
        session.delete(session.get(BoardRow, "bx-14"))
    with session_scope(factory) as session:
        assert session.scalars(select(SlotRow)).all() == []
        assert session.scalars(select(BoardStateRow)).all() == []


def test_a_slot_cannot_hang_off_a_board_that_is_not_there(factory):
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(SlotRow(board_tag="bx-99", name="fmc0", connector="fmc-hpc"))


def test_a_failed_write_leaves_nothing_behind(factory):
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(board_row())
        session.add(board_row(tag="bx-15", serial="210308CD5678", outlet=9))
    with session_scope(factory) as session:
        assert session.scalars(select(BoardRow)).all() == []


def test_boards_can_be_found_by_device(factory):
    with session_scope(factory) as session:
        session.add(board_row())
        session.add(board_row(tag="bx-15", shelf=5, serial="210308CD5678", outlet=9))
    with session_scope(factory) as session:
        found = session.scalars(select(BoardRow).where(BoardRow.device == "xc7k325t")).all()
        assert {row.tag for row in found} == {"bx-14", "bx-15"}
