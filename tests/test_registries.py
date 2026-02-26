from datetime import UTC, datetime, timedelta

import pytest

from boardsetup import kintex
from fpga.inventory.book import BoardBook
from fpga.leases.store import LeaseStore
from fpga.persistence.unit import BenchDatabase
from fpga.registries import BoardRegistry, LeaseRegistry
from fpga.services import inventory, leasing
from fpga.services.adapters import BookOverRepository, LeaseStoreOverRepository

WHEN = datetime(2026, 3, 2, 9, 0, tzinfo=UTC)


@pytest.fixture()
def book():
    made = BoardBook()
    made.add(kintex("bx-14"), when=WHEN - timedelta(days=1))
    return made


@pytest.fixture()
def unit():
    database = BenchDatabase.in_memory()
    database.create_schema()
    with database.unit() as open_unit:
        inventory.add_board(open_unit, kintex("bx-14"), WHEN - timedelta(days=1))
        yield open_unit


def test_the_in_memory_book_satisfies_the_board_registry(book):
    assert isinstance(book, BoardRegistry)


def test_the_repository_adapter_satisfies_the_board_registry(unit):
    assert isinstance(BookOverRepository(unit), BoardRegistry)


def test_the_in_memory_store_satisfies_the_lease_registry():
    assert isinstance(LeaseStore(), LeaseRegistry)


def test_the_repository_adapter_satisfies_the_lease_registry(unit):
    assert isinstance(LeaseStoreOverRepository(unit), LeaseRegistry)


def test_something_missing_a_method_is_not_a_board_registry():
    class Half:
        def has(self, tag):
            return False

        def board(self, tag):
            raise NotImplementedError

    assert not isinstance(Half(), BoardRegistry)


def test_both_registries_answer_the_same_way_about_a_board(book, unit):
    over_database = BookOverRepository(unit)
    assert book.has("bx-14") == over_database.has("bx-14")
    assert book.board("bx-14").model == over_database.board("bx-14").model
    assert book.state("bx-14").state == over_database.state("bx-14").state


def test_both_registries_agree_about_a_board_nobody_has(book, unit):
    assert book.has("bx-99") is False
    assert BookOverRepository(unit).has("bx-99") is False


def test_a_move_through_either_registry_lands_in_the_same_state(book, unit):
    book.move_to("bx-14", "maintenance", WHEN, reason="fan", actor="ops")
    over_database = BookOverRepository(unit)
    over_database.move_to("bx-14", "maintenance", WHEN, reason="fan", actor="ops")
    assert book.state("bx-14").state == over_database.state("bx-14").state


def test_the_lease_registries_agree_about_an_empty_board(unit):
    store = LeaseStore()
    assert store.for_board("bx-14", WHEN) is None
    assert LeaseStoreOverRepository(unit).for_board("bx-14", WHEN) is None


def test_a_lease_taken_through_the_database_shows_up_on_the_adapter(unit):
    leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN, minutes=30)
    held = LeaseStoreOverRepository(unit).for_board("bx-14", WHEN)
    assert held is not None
    assert held.holder == "team-a"


def test_releasing_through_the_adapter_gives_the_board_back(unit):
    taken = leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN, minutes=30)
    adapter = LeaseStoreOverRepository(unit)
    adapter.release(taken.id, WHEN + timedelta(minutes=5), "done")
    assert adapter.for_board("bx-14", WHEN + timedelta(minutes=6)) is None
