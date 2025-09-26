from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

import pytest

from fpga.errors import ValidationError
from fpga.records import PerBoard, board_key, describe_tally, in_window, tally

NOW = datetime(2025, 9, 26, 10, 0, tzinfo=UTC)


@dataclass(frozen=True)
class Note:
    at: datetime
    text: str


def test_tags_normalise_however_they_were_written():
    assert board_key("BX-0014") == "bx-14"
    assert board_key(" bx-14 ") == "bx-14"


def test_a_tag_that_is_not_one_is_refused():
    with pytest.raises(ValidationError):
        board_key("board 14")


def test_a_window_keeps_what_is_inside_it():
    notes = [
        Note(NOW - timedelta(hours=5), "old"),
        Note(NOW - timedelta(minutes=30), "recent"),
        Note(NOW, "now"),
    ]
    inside = in_window(notes, NOW, timedelta(hours=1), at=lambda note: note.at)
    assert [note.text for note in inside] == ["recent", "now"]


def test_a_window_of_no_length_is_a_bug():
    with pytest.raises(ValidationError):
        in_window([], NOW, timedelta(0), at=lambda note: note.at)


def test_a_tally_counts_and_describes():
    counts = tally(["available", "faulted", "available"])
    assert counts == {"available": 2, "faulted": 1}
    assert describe_tally(counts) == "2 available, 1 faulted"


def test_an_empty_tally_says_what_it_was_told_to():
    assert describe_tally({}) == "nothing"
    assert describe_tally({}, empty="nothing being watched") == "nothing being watched"


def test_entries_are_kept_per_board_in_order():
    held: PerBoard[Note] = PerBoard()
    held.append("bx-14", Note(NOW, "first"))
    held.append("bx-14", Note(NOW, "second"))
    held.append("BX-0015", Note(NOW, "other"))
    assert [note.text for note in held.items("bx-14")] == ["first", "second"]
    assert held.boards() == ("bx-14", "bx-15")
    assert len(held) == 3


def test_the_latest_entry_comes_back():
    held: PerBoard[Note] = PerBoard()
    assert held.latest("bx-14") is None
    held.append("bx-14", Note(NOW, "first"))
    held.append("bx-14", Note(NOW, "second"))
    assert held.latest("bx-14").text == "second"


def test_everything_comes_back_board_by_board():
    held: PerBoard[Note] = PerBoard()
    held.append("bx-15", Note(NOW, "b"))
    held.append("bx-14", Note(NOW, "a"))
    assert [note.text for note in held.all()] == ["a", "b"]


def test_a_cap_drops_the_oldest_entries():
    held: PerBoard[Note] = PerBoard(keep=2)
    for index in range(5):
        held.append("bx-14", Note(NOW, str(index)))
    assert [note.text for note in held.items("bx-14")] == ["3", "4"]


def test_a_cap_of_one_is_refused():
    with pytest.raises(ValidationError):
        PerBoard(keep=1)


def test_dropping_a_board_says_how_many_went():
    held: PerBoard[Note] = PerBoard()
    held.append("bx-14", Note(NOW, "a"))
    held.append("bx-14", Note(NOW, "b"))
    assert held.drop("bx-14") == 2
    assert held.boards() == ()
    assert held.drop("bx-14") == 0


def test_replacing_a_board_s_entries():
    held: PerBoard[Note] = PerBoard()
    held.append("bx-14", Note(NOW, "a"))
    held.replace("bx-14", [Note(NOW, "b")])
    assert [note.text for note in held.items("bx-14")] == ["b"]
    held.replace("bx-14", [])
    assert "bx-14" not in held


def test_pruning_keeps_what_the_predicate_wants():
    held: PerBoard[Note] = PerBoard()
    held.append("bx-14", Note(NOW - timedelta(hours=2), "old"))
    held.append("bx-14", Note(NOW, "new"))
    held.append("bx-15", Note(NOW - timedelta(hours=3), "old"))
    dropped = held.prune(lambda note: note.at >= NOW - timedelta(hours=1))
    assert dropped == 2
    assert held.boards() == ("bx-14",)


def test_membership_reads_the_tag():
    held: PerBoard[Note] = PerBoard()
    held.append("bx-14", Note(NOW, "a"))
    assert "BX-0014" in held
    assert "bx-15" not in held
    assert 14 not in held
