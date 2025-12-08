from datetime import UTC, datetime, timedelta

import pytest

from boardsetup import artix, kintex
from fpga.config import BenchSettings
from fpga.errors import NotFound, ValidationError
from fpga.inventory.state import BoardState
from fpga.leases.models import Lease
from fpga.persistence.unit import BenchDatabase

NOW = datetime(2025, 12, 11, 10, 0, tzinfo=UTC)


@pytest.fixture()
def database(tmp_path):
    settings = BenchSettings(database_url=f"sqlite+pysqlite:///{tmp_path / 'bench.db'}")
    live = BenchDatabase(settings)
    live.create_schema()
    return live


def available():
    return BoardState(state="available", since=NOW)


def lease(board="bx-14"):
    return Lease(
        id="lease-00001",
        board=board,
        holder="team-a",
        purpose="capture",
        taken_at=NOW,
        expires_at=NOW + timedelta(hours=1),
    )


def test_a_unit_commits_on_the_way_out(database):
    with database.unit() as unit:
        unit.boards.add(kintex("bx-14"), available())
    with database.unit() as unit:
        assert unit.boards.get("bx-14").tag == "bx-14"


def test_a_unit_that_raises_writes_nothing(database):
    with pytest.raises(RuntimeError), database.unit() as unit:
        unit.boards.add(kintex("bx-14"), available())
        raise RuntimeError("something went wrong halfway")
    with database.unit() as unit:
        assert unit.boards.all() == ()


def test_four_writes_land_together_or_not_at_all(database):
    with pytest.raises(RuntimeError), database.unit() as unit:
        unit.boards.add(kintex("bx-14"), available())
        unit.leases.add(lease())
        raise RuntimeError("the job blew up")
    with database.unit() as unit:
        assert unit.boards.all() == ()
        assert unit.leases.active(NOW) == ()


def test_the_board_the_lease_and_the_job_all_land(database):
    with database.unit() as unit:
        unit.boards.add(kintex("bx-14"), available())
        unit.leases.add(lease())
        unit.boards.save_state("bx-14", BoardState(state="leased", since=NOW))
    with database.unit() as unit:
        assert unit.boards.state("bx-14").state == "leased"
        assert unit.leases.for_board("bx-14", NOW) is not None


def test_a_unit_can_be_rolled_back_by_hand(database):
    with database.unit() as unit:
        unit.boards.add(kintex("bx-14"), available())
        unit.rollback()
    with database.unit() as unit:
        assert unit.boards.all() == ()


def test_a_unit_that_committed_does_not_commit_again(database):
    with database.unit() as unit:
        unit.boards.add(kintex("bx-14"), available())
        unit.commit()
        assert unit.committed
    with database.unit() as unit:
        assert len(unit.boards.all()) == 1


def test_a_flush_makes_a_write_visible_inside_the_same_unit(database):
    with database.unit() as unit:
        unit.boards.add(kintex("bx-14"), available())
        unit.flush()
        assert unit.boards.get("bx-14").model == "rx-carrier-b"


def test_a_unit_outside_its_block_refuses_to_be_used(database):
    unit = database.unit()
    with pytest.raises(ValidationError):
        unit.boards.all()


def test_a_unit_is_usable_again_after_it_is_reopened(database):
    unit = database.unit()
    with unit:
        unit.boards.add(kintex("bx-14"), available())
    with unit:
        assert len(unit.boards.all()) == 1


def test_an_in_memory_database_holds_a_whole_bench():
    live = BenchDatabase.in_memory()
    with live.unit() as unit:
        unit.boards.add(kintex("bx-14"), available())
        unit.boards.add(artix("bx-15"), available())
    with live.unit() as unit:
        assert len(unit.boards.all()) == 2
    live.dispose()


def test_a_missing_row_still_raises_through_the_unit(database):
    with database.unit() as unit, pytest.raises(NotFound):
        unit.boards.get("bx-99")


def test_the_repositories_come_off_the_same_session(database):
    with database.unit() as unit:
        assert unit.boards.session is unit.leases.session
        assert unit.jobs.session is unit.artifacts.session
