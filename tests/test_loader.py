import json
from datetime import UTC, datetime

import pytest

from boardsetup import artix, kintex
from fpga.errors import ConfigurationError
from fpga.inventory.board_json import board_from_json, board_to_json
from fpga.inventory.book import BoardBook
from fpga.inventory.loader import dump_bench, load_bench, read_bench

WHEN = datetime(2025, 7, 18, 8, 0, tzinfo=UTC)

BENCH = {
    "site": "lab-a",
    "boards": [
        {
            "asset": "bx-14",
            "model": "rx-carrier-b",
            "revision": "c",
            "part": "XC7K325T-2FFG900I",
            "flash": "n25q128a13",
            "location": "rack-2/shelf-04/slot-3",
            "slots": [
                {"name": "fmc0", "connector": "fmc-hpc", "fitted": "fmc-adc-4ch-14b"},
                {"name": "fmc1", "connector": "fmc-lpc"},
            ],
            "adapter": {"serial": "210308AB1234", "model": "jtag-hs2", "port": "usb-3"},
            "pdu": "pdu-a/outlet-07",
            "notes": "the one with the short ribbon",
        },
        {
            "asset": "bx-15",
            "model": "tx-carrier-a",
            "part": "XC7A100T-2FGG484I",
            "flash": "w25q64jv",
            "location": "rack-4/shelf-01/slot-1",
            "state": "maintenance",
            "reason": "waiting on a new header",
        },
    ],
}


def text(document=None):
    return json.dumps(document if document is not None else BENCH)


def test_a_bench_file_becomes_a_book():
    book = read_bench(text(), when=WHEN)
    assert len(book) == 2
    assert book.board("bx-14").model == "rx-carrier-b"
    assert book.board("bx-14").revision == "c"
    assert book.board("bx-14").notes == "the one with the short ribbon"


def test_slots_and_cards_come_through():
    board = read_bench(text(), when=WHEN).board("bx-14")
    assert board.slot("fmc0").fitted == "fmc-adc-4ch-14b"
    assert board.slot("fmc1").empty


def test_the_cable_and_the_outlet_come_through():
    board = read_bench(text(), when=WHEN).board("bx-14")
    assert board.adapter is not None
    assert board.adapter.serial == "210308AB1234"
    assert board.adapter.port == "usb-3"
    assert str(board.pdu) == "pdu-a/outlet-07"


def test_a_board_can_arrive_out_of_service_with_its_reason():
    book = read_bench(text(), when=WHEN)
    state = book.state("bx-15")
    assert state.state == "maintenance"
    assert state.reason == "waiting on a new header"


def test_defaults_fill_in_for_everything_optional():
    board = read_bench(text(), when=WHEN).board("bx-15")
    assert board.revision == "a"
    assert board.vadj_millivolts == 1800
    assert board.max_tck_hz == 30_000_000
    assert board.adapter is None
    assert board.pdu is None


def test_a_file_that_is_not_json_is_refused():
    with pytest.raises(ConfigurationError):
        read_bench("{not json")


def test_a_file_without_boards_is_refused():
    with pytest.raises(ConfigurationError):
        read_bench(json.dumps({"site": "lab-a"}))
    with pytest.raises(ConfigurationError):
        read_bench(json.dumps([1, 2, 3]))


def test_a_board_entry_that_is_not_an_object_is_refused():
    with pytest.raises(ConfigurationError):
        read_bench(json.dumps({"boards": ["bx-14"]}))


def test_a_board_missing_a_field_names_the_board_and_the_field():
    document = {"boards": [{"asset": "bx-14", "model": "rx-carrier-b"}]}
    with pytest.raises(ConfigurationError) as caught:
        read_bench(json.dumps(document))
    assert caught.value.details["board"] == "bx-14"
    assert "part" in caught.value.details["missing"]


def test_a_board_with_a_bad_value_names_the_board():
    document = {"boards": [dict(BENCH["boards"][0], part="XC7A100T")]}
    with pytest.raises(ConfigurationError) as caught:
        read_bench(json.dumps(document))
    assert caught.value.details["board"] == "bx-14"


def test_two_boards_in_one_slot_are_refused_by_the_book():
    clash = dict(BENCH["boards"][1], location="rack-2/shelf-04/slot-3")
    document = {"boards": [BENCH["boards"][0], clash]}
    with pytest.raises(ConfigurationError):
        read_bench(json.dumps(document))


def test_a_file_is_loaded_from_disk(tmp_path):
    path = tmp_path / "bench.json"
    path.write_text(text(), encoding="utf-8")
    assert len(load_bench(path, when=WHEN)) == 2


def test_a_missing_file_is_refused(tmp_path):
    with pytest.raises(ConfigurationError):
        load_bench(tmp_path / "nothing.json")


def test_a_book_round_trips_through_the_document():
    book = read_bench(text(), when=WHEN)
    again = read_bench(dump_bench(book), when=WHEN)
    assert again.tags() == book.tags()
    assert again.board("bx-14").describe() == book.board("bx-14").describe()
    assert again.state("bx-15").state == "maintenance"


def test_the_dump_leaves_out_what_is_not_there():
    book = BoardBook(when=WHEN)
    book.add(artix("bx-15", serial=None, outlet=None, slots=()))
    entry = json.loads(dump_bench(book))["boards"][0]
    assert "adapter" not in entry
    assert "pdu" not in entry
    assert "slots" not in entry
    assert "notes" not in entry


def test_one_board_round_trips_on_its_own():
    board = kintex("bx-14")
    assert board_from_json(board_to_json(board)).describe() == board.describe()


def test_an_empty_slot_is_written_without_a_fitted_key():
    entry = board_to_json(kintex("bx-14"))
    assert entry["slots"][1] == {"name": "fmc1", "connector": "fmc-lpc"}
