from datetime import UTC, datetime, timedelta

import pytest

from boardsetup import artix, kintex
from fpga.errors import NotFound, ValidationError
from fpga.inventory.boards import BoardSlot
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory, upkeep

WHEN = datetime(2026, 4, 8, 9, 0, tzinfo=UTC)
YEAR = timedelta(days=365)


def plain(asset):
    """A board with nothing on it that measures anything."""
    return artix(
        asset,
        slots=(BoardSlot("pmod0", "pmod", "pmod-rs485"), BoardSlot("fmc0", "fmc-lpc")),
    )


@pytest.fixture()
def unit():
    database = BenchDatabase.in_memory()
    with database.unit() as open_unit:
        inventory.add_board(open_unit, kintex("bx-14"), WHEN - timedelta(days=400))
        inventory.add_board(open_unit, plain("bx-15"), WHEN - timedelta(days=400))
        yield open_unit


def visit(unit, board="bx-14", kind="repair", when=None, **rest):
    return upkeep.log_visit(unit, board, kind, when or WHEN, rest.pop("by", "sam"), **rest)


def test_a_visit_is_written_down(unit):
    record = visit(unit, notes="reseated the fmc")
    assert record.board == "bx-14"
    assert unit.maintenance.for_board("bx-14")[0].notes == "reseated the fmc"


def test_a_kind_the_bench_does_not_know_is_refused(unit):
    with pytest.raises(ValidationError):
        visit(unit, kind="percussive")


def test_a_visit_to_a_board_nobody_has_is_refused(unit):
    with pytest.raises(NotFound):
        visit(unit, board="bx-99")


def test_visits_come_back_oldest_first(unit):
    visit(unit, when=WHEN - timedelta(days=2), notes="first")
    visit(unit, when=WHEN, notes="second")
    assert [entry.notes for entry in unit.maintenance.for_board("bx-14")] == ["first", "second"]


def test_the_last_visit_of_a_kind_is_the_newest(unit):
    visit(unit, kind="calibration", when=WHEN - timedelta(days=200))
    visit(unit, kind="repair", when=WHEN)
    last = unit.maintenance.last("bx-14", "calibration")
    assert last is not None
    assert last.at == WHEN - timedelta(days=200)


def test_a_board_with_no_visits_has_no_last(unit):
    assert unit.maintenance.last("bx-15") is None


def test_the_whole_log_loads_as_a_book(unit):
    visit(unit, board="bx-14")
    visit(unit, board="bx-15")
    assert set(unit.maintenance.book().boards()) == {"bx-14", "bx-15"}


def test_a_board_with_a_converter_needs_calibration(unit):
    state = upkeep.calibration(unit, "bx-14", WHEN)
    assert state.required is True
    assert state.never is True
    assert state.valid is False


def test_a_board_with_no_measuring_card_needs_none(unit):
    state = upkeep.calibration(unit, "bx-15", WHEN)
    assert state.required is False
    assert state.valid is True


def test_a_fresh_calibration_holds(unit):
    visit(unit, kind="calibration", when=WHEN - timedelta(days=30))
    state = upkeep.calibration(unit, "bx-14", WHEN)
    assert state.valid is True
    assert state.due_at == WHEN - timedelta(days=30) + YEAR


def test_a_calibration_over_a_year_old_has_run_out(unit):
    visit(unit, kind="calibration", when=WHEN - timedelta(days=400))
    assert upkeep.calibration(unit, "bx-14", WHEN).valid is False


def test_the_newest_calibration_is_the_one_that_counts(unit):
    visit(unit, kind="calibration", when=WHEN - timedelta(days=400))
    visit(unit, kind="calibration", when=WHEN - timedelta(days=10))
    assert upkeep.calibration(unit, "bx-14", WHEN).valid is True


def test_a_repair_does_not_count_as_a_calibration(unit):
    visit(unit, kind="repair", when=WHEN - timedelta(days=1))
    assert upkeep.calibration(unit, "bx-14", WHEN).valid is False


def test_the_overdue_list_names_the_board_that_never_was(unit):
    assert [state.board for state in upkeep.overdue(unit, WHEN)] == ["bx-14"]


def test_a_calibrated_board_leaves_the_overdue_list(unit):
    visit(unit, kind="calibration", when=WHEN - timedelta(days=5))
    assert upkeep.overdue(unit, WHEN) == ()


def test_the_state_reads_as_one_line(unit):
    visit(unit, kind="calibration", when=WHEN - timedelta(days=5))
    assert "calibrated 2026-04-03" in upkeep.calibration(unit, "bx-14", WHEN).describe()


def test_a_board_needing_nothing_says_so(unit):
    assert upkeep.calibration(unit, "bx-15", WHEN).describe().endswith("needs no calibration")


def test_returning_to_service_logs_and_moves_in_one_go(unit):
    inventory.move_state(unit, "bx-14", "maintenance", WHEN, reason="fan", actor="sam")
    upkeep.return_to_service(unit, "bx-14", WHEN, "sam", notes="new fan fitted")
    assert unit.boards.state("bx-14").state == "available"
    assert unit.maintenance.for_board("bx-14")[-1].notes == "new fan fitted"


def test_returning_a_faulted_board_clears_it(unit):
    inventory.fault_board(unit, "bx-14", WHEN, "chain went away", actor="runner")
    upkeep.return_to_service(unit, "bx-14", WHEN, "sam", faults_cleared=1)
    assert unit.boards.state("bx-14").state == "available"
    assert unit.maintenance.book().faults_cleared_for("bx-14") == 1


def test_a_calibration_visit_can_be_the_thing_that_returns_it(unit):
    inventory.move_state(unit, "bx-14", "maintenance", WHEN, reason="due", actor="sam")
    upkeep.return_to_service(unit, "bx-14", WHEN, "sam", kind="calibration")
    assert upkeep.calibration(unit, "bx-14", WHEN).valid is True


def test_a_board_with_two_converters_still_needs_one_calibration(unit):
    inventory.add_board(
        unit,
        kintex(
            "bx-16",
            location="rack-2/shelf-05/slot-1",
            serial="210308FF9999",
            outlet=11,
            slots=(
                BoardSlot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b"),
                BoardSlot("fmc1", "fmc-hpc", "fmc-adc-4ch-14b"),
            ),
        ),
        WHEN - timedelta(days=10),
    )
    visit(unit, board="bx-16", kind="calibration", when=WHEN - timedelta(days=1))
    assert upkeep.calibration(unit, "bx-16", WHEN).valid is True
