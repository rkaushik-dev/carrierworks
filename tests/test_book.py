from datetime import UTC, datetime, timedelta

import pytest

from boardsetup import artix, kintex
from fpga.capabilities import RequirementSet
from fpga.errors import ConfigurationError, NotFound, ValidationError
from fpga.inventory.book import BoardBook

WHEN = datetime(2025, 7, 16, 9, 0, tzinfo=UTC)


def book():
    return BoardBook(when=WHEN)


def test_a_board_goes_in_and_comes_back_out():
    shelf = book()
    shelf.add(kintex("bx-14"))
    assert shelf.board("BX-0014").model == "rx-carrier-b"
    assert shelf.has("bx-14")
    assert len(shelf) == 1


def test_a_board_nobody_added_is_a_not_found():
    with pytest.raises(NotFound):
        book().board("bx-99")


def test_a_tag_cannot_be_used_twice():
    shelf = book()
    shelf.add(kintex("bx-14"))
    with pytest.raises(ConfigurationError):
        shelf.add(kintex("bx-14", location="rack-2/shelf-05/slot-1"))


def test_two_boards_cannot_be_in_one_slot():
    shelf = book()
    shelf.add(kintex("bx-14", location="rack-2/shelf-04/slot-3"))
    with pytest.raises(ConfigurationError) as caught:
        shelf.add(artix("bx-15", location="rack-2/shelf-04/slot-3"))
    assert caught.value.details["holder"] == "bx-14"


def test_one_cable_cannot_be_on_two_boards():
    shelf = book()
    shelf.add(kintex("bx-14", serial="210308AB1234"))
    with pytest.raises(ConfigurationError) as caught:
        shelf.add(artix("bx-15", location="rack-2/shelf-05/slot-1", serial="210308AB1234"))
    assert caught.value.details["serial"] == "210308AB1234"


def test_one_outlet_cannot_feed_two_boards():
    shelf = book()
    shelf.add(kintex("bx-14", outlet=7))
    with pytest.raises(ConfigurationError):
        shelf.add(artix("bx-15", location="rack-2/shelf-05/slot-1", outlet=7, pdu_name="pdu-a"))


def test_boards_without_cables_or_outlets_do_not_collide():
    shelf = book()
    shelf.add(kintex("bx-14", serial=None, outlet=None))
    shelf.add(artix("bx-15", location="rack-2/shelf-05/slot-1", serial=None, outlet=None))
    assert len(shelf) == 2


def test_a_board_starts_available():
    shelf = book()
    shelf.add(kintex("bx-14"))
    assert shelf.state("bx-14").usable
    assert shelf.available() == shelf.all()


def test_a_board_can_be_added_already_out_of_service():
    shelf = book()
    shelf.add(kintex("bx-14"), state="maintenance")
    assert shelf.state("bx-14").out_of_service
    assert shelf.available() == ()
    assert shelf.state("bx-14").reason == "added to the bench"


def test_state_moves_go_through_the_state_machine():
    shelf = book()
    shelf.add(kintex("bx-14"))
    shelf.move_to("bx-14", "leased", WHEN + timedelta(minutes=5))
    assert shelf.state("bx-14").state == "leased"
    assert shelf.in_state("leased")[0].tag == "bx-14"
    with pytest.raises(ValidationError):
        shelf.move_to("bx-14", "quarantined", WHEN + timedelta(minutes=6), reason="no")


def test_replacing_a_board_keeps_its_state():
    shelf = book()
    shelf.add(kintex("bx-14"))
    shelf.move_to("bx-14", "leased", WHEN + timedelta(minutes=5))
    shelf.replace(shelf.board("bx-14").with_card("fmc1", "fmc-adc-2ch-16b"))
    assert shelf.state("bx-14").state == "leased"
    assert len(shelf.board("bx-14").fitted_cards()) == 2


def test_replacing_a_board_into_an_occupied_slot_is_refused():
    shelf = book()
    shelf.add(kintex("bx-14", location="rack-2/shelf-04/slot-3"))
    shelf.add(artix("bx-15", location="rack-2/shelf-05/slot-1"))
    moved = artix("bx-15", location="rack-2/shelf-04/slot-3")
    with pytest.raises(ConfigurationError):
        shelf.replace(moved)


def test_replacing_a_board_that_is_not_there_is_a_not_found():
    with pytest.raises(NotFound):
        book().replace(kintex("bx-14"))


def test_a_removed_board_takes_its_state_with_it():
    shelf = book()
    shelf.add(kintex("bx-14"))
    assert shelf.remove("bx-14").tag == "bx-14"
    assert not shelf.has("bx-14")
    with pytest.raises(NotFound):
        shelf.remove("bx-14")


def test_boards_come_back_sorted_by_tag():
    shelf = book()
    shelf.add(artix("bx-15", location="rack-2/shelf-05/slot-1", serial=None, outlet=None))
    shelf.add(kintex("bx-14", serial=None, outlet=None))
    assert shelf.tags() == ("bx-14", "bx-15")


def test_boards_can_be_found_by_rack_and_by_device():
    shelf = book()
    shelf.add(kintex("bx-14", location="rack-2/shelf-04/slot-3", serial=None, outlet=None))
    shelf.add(artix("bx-15", location="rack-4/shelf-01/slot-1", serial=None, outlet=None))
    assert [board.tag for board in shelf.in_rack("rack-4")] == ["bx-15"]
    assert [board.tag for board in shelf.with_device("xc7k325t")] == ["bx-14"]


def test_matching_only_offers_boards_that_are_free():
    shelf = book()
    shelf.add(kintex("bx-14", serial=None, outlet=None))
    shelf.add(artix("bx-15", location="rack-2/shelf-05/slot-1", serial=None, outlet=None))
    wanted = RequirementSet.parse(["family=kintex7"])
    assert [board.tag for board in shelf.matching(wanted)] == ["bx-14"]
    shelf.move_to("bx-14", "leased", WHEN + timedelta(minutes=1))
    assert shelf.matching(wanted) == ()
    assert [board.tag for board in shelf.matching(wanted, only_available=False)] == ["bx-14"]


def test_the_book_summarises_itself():
    shelf = book()
    shelf.add(kintex("bx-14", serial=None, outlet=None))
    shelf.add(artix("bx-15", location="rack-2/shelf-05/slot-1", serial=None, outlet=None))
    shelf.move_to("bx-15", "faulted", WHEN + timedelta(minutes=2), reason="chain")
    assert shelf.describe() == "2 boards: 1 available, 1 faulted"
    assert len(shelf.summaries()) == 2
    assert shelf.locations()["bx-14"] == "rack-2/shelf-04/slot-3"


def test_a_board_needs_a_time_to_arrive():
    with pytest.raises(ConfigurationError):
        BoardBook().add(kintex("bx-14"))
