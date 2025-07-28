from datetime import UTC, datetime, timedelta

import pytest

from boardsetup import artix, kintex
from fpga.errors import ValidationError
from fpga.inventory.boards import BoardSlot
from fpga.inventory.maintenance import (
    CALIBRATION_INTERVAL,
    MaintenanceBook,
    MaintenanceRecord,
    calibration_interval,
)

NOW = datetime(2025, 7, 25, 10, 0, tzinfo=UTC)


def book():
    return MaintenanceBook()


def test_a_record_says_who_did_what_and_when():
    entry = MaintenanceRecord(
        board="BX-0014",
        kind="repair",
        at=NOW,
        by="dana",
        notes="reflowed the jtag header",
    )
    assert entry.board == "bx-14"
    assert entry.describe() == "bx-14 repair on 2025-07-25 by dana: reflowed the jtag header"


def test_a_record_without_notes_still_describes_itself():
    entry = MaintenanceRecord(board="bx-14", kind="inspection", at=NOW, by="dana")
    assert entry.describe() == "bx-14 inspection on 2025-07-25 by dana"


@pytest.mark.parametrize(
    ("kind", "by", "cleared"),
    [("exorcism", "dana", 0), ("repair", "  ", 0), ("repair", "dana", -1)],
)
def test_a_nonsense_record_is_refused(kind, by, cleared):
    with pytest.raises(ValidationError):
        MaintenanceRecord(board="bx-14", kind=kind, at=NOW, by=by, faults_cleared=cleared)


def test_maintenance_times_are_in_utc():
    with pytest.raises(ValidationError):
        MaintenanceRecord(board="bx-14", kind="repair", at=datetime(2025, 7, 25), by="dana")


def test_records_come_back_per_board():
    live = book()
    live.add("bx-14", "repair", NOW, "dana")
    live.add("bx-15", "inspection", NOW, "sam")
    assert len(live.for_board("bx-14")) == 1
    assert live.boards() == ("bx-14", "bx-15")
    assert len(live.all()) == 2


def test_the_last_visit_of_a_kind_is_found():
    live = book()
    live.add("bx-14", "repair", NOW - timedelta(days=40), "dana")
    live.add("bx-14", "calibration", NOW - timedelta(days=10), "sam")
    live.add("bx-14", "repair", NOW - timedelta(days=2), "dana")
    assert live.last("bx-14").kind == "repair"
    assert live.last("bx-14", "calibration").by == "sam"
    assert live.last("bx-14", "upgrade") is None
    assert live.last("bx-99") is None


def test_a_board_with_a_converter_needs_calibrating():
    assert calibration_interval(kintex("bx-14")) == CALIBRATION_INTERVAL


def test_a_bare_carrier_does_not():
    assert calibration_interval(kintex("bx-14", slots=())) is None


def test_a_board_with_only_a_digital_card_does_not():
    board = artix("bx-15", slots=(BoardSlot("pmod0", "pmod", "pmod-rs485"),))
    assert calibration_interval(board) is None


def test_a_board_with_a_converter_pmod_does():
    board = artix("bx-15", slots=(BoardSlot("pmod0", "pmod", "pmod-ad2"),))
    assert calibration_interval(board) == CALIBRATION_INTERVAL


def test_a_board_that_needs_calibration_and_has_never_had_any_is_due_now():
    board = kintex("bx-14")
    live = book()
    assert live.calibration_due(board, NOW) == NOW
    assert not live.calibration_valid(board, NOW)


def test_a_freshly_calibrated_board_is_good_for_a_year():
    board = kintex("bx-14")
    live = book()
    live.add("bx-14", "calibration", NOW - timedelta(days=30), "sam")
    due = live.calibration_due(board, NOW)
    assert due == NOW - timedelta(days=30) + CALIBRATION_INTERVAL
    assert live.calibration_valid(board, NOW)


def test_a_board_calibrated_too_long_ago_is_not_valid():
    board = kintex("bx-14")
    live = book()
    live.add("bx-14", "calibration", NOW - timedelta(days=400), "sam")
    assert not live.calibration_valid(board, NOW)


def test_a_board_that_needs_no_calibration_is_always_valid():
    bare = kintex("bx-14", slots=())
    live = book()
    assert live.calibration_due(bare, NOW) is None
    assert live.calibration_valid(bare, NOW)


def test_fitting_a_converter_makes_a_board_immediately_due():
    bare = kintex("bx-14", slots=(BoardSlot("fmc0", "fmc-hpc"),))
    live = book()
    live.add("bx-14", "inspection", NOW - timedelta(days=5), "dana")
    assert live.calibration_valid(bare, NOW)
    fitted = bare.with_card("fmc0", "fmc-adc-4ch-14b")
    assert not live.calibration_valid(fitted, NOW)


def test_overdue_boards_come_back_oldest_first():
    live = book()
    live.add("bx-14", "calibration", NOW - timedelta(days=500), "sam")
    boards = (
        kintex("bx-14"),
        artix("bx-15", slots=(BoardSlot("pmod0", "pmod", "pmod-ad2"),)),
        artix("bx-16", location="rack-4/shelf-02/slot-1", serial=None, outlet=None, slots=()),
    )
    overdue = [board.tag for board in live.overdue(boards, NOW)]
    assert overdue == ["bx-14", "bx-15"]


def test_cleared_faults_are_totalled_for_a_board():
    live = book()
    live.add("bx-14", "repair", NOW - timedelta(days=9), "dana", faults_cleared=3)
    live.add("bx-14", "repair", NOW, "dana", faults_cleared=2)
    assert live.faults_cleared_for("bx-14") == 5
    assert live.faults_cleared_for("bx-15") == 0
