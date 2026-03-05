from datetime import UTC, datetime, timedelta

import pytest

from boardsetup import artix, kintex
from fpga.audit import AuditEntry  # noqa: F401  (the tick writes these)
from fpga.config import BenchSettings
from fpga.errors import NotFound, ValidationError
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory, leasing
from fpga.services import thermal as thermal_service
from fpga.temperature import ThermalLimits
from fpga.thermal.alarms import AlarmBook

WHEN = datetime(2026, 3, 10, 14, 0, tzinfo=UTC)
LIMITS = ThermalLimits(warn_mc=76_000, critical_mc=86_000, shutdown_mc=90_000)


@pytest.fixture()
def unit():
    database = BenchDatabase.in_memory()
    database.create_schema()
    with database.unit() as open_unit:
        inventory.add_board(open_unit, kintex("bx-14"), WHEN - timedelta(days=1))
        inventory.add_board(open_unit, artix("bx-15"), WHEN - timedelta(days=1))
        yield open_unit


def stream(unit, board, *readings, start=None, every=timedelta(seconds=20)):
    """Record a run of readings ending at WHEN, one every twenty seconds."""
    first = start or WHEN - every * (len(readings) - 1)
    for index, value in enumerate(readings):
        thermal_service.record(unit, board, first + every * index, value)
    return first


def test_a_reading_is_stored_and_read_back(unit):
    thermal_service.record(unit, "bx-14", WHEN, 64_500)
    latest = unit.thermal.latest("bx-14")
    assert latest is not None
    assert latest.millidegrees == 64_500
    assert latest.source == "sysmon"


def test_a_reading_for_a_board_nobody_has_is_refused(unit):
    with pytest.raises(NotFound):
        thermal_service.record(unit, "bx-99", WHEN, 64_500)


def test_a_reading_that_is_not_a_temperature_is_refused(unit):
    with pytest.raises(ValidationError):
        thermal_service.record(unit, "bx-14", WHEN, 400_000)


def test_the_latest_reading_is_the_newest_one(unit):
    stream(unit, "bx-14", 60_000, 62_000, 66_000)
    latest = unit.thermal.latest("bx-14")
    assert latest is not None
    assert latest.millidegrees == 66_000


def test_a_board_with_no_readings_has_no_latest(unit):
    assert unit.thermal.latest("bx-15") is None


def test_readings_come_back_in_time_order(unit):
    stream(unit, "bx-14", 60_000, 62_000, 66_000)
    found = unit.thermal.since(WHEN - timedelta(hours=1), "bx-14")
    assert [sample.millidegrees for sample in found] == [60_000, 62_000, 66_000]


def test_a_window_leaves_out_what_is_older_than_it(unit):
    thermal_service.record(unit, "bx-14", WHEN - timedelta(hours=2), 55_000)
    stream(unit, "bx-14", 60_000, 62_000)
    log = thermal_service.load_window(unit, WHEN, timedelta(minutes=15))
    assert len(log.since("bx-14", WHEN - timedelta(minutes=15))) == 2


def test_one_query_loads_every_board(unit):
    stream(unit, "bx-14", 60_000, 61_000)
    stream(unit, "bx-15", 70_000, 71_000)
    log = thermal_service.load_window(unit, WHEN, timedelta(minutes=15))
    assert set(log.boards()) == {"bx-14", "bx-15"}


def test_a_source_other_than_the_die_sensor_is_kept(unit):
    thermal_service.record(unit, "bx-14", WHEN, 40_000, source="inlet")
    latest = unit.thermal.latest("bx-14")
    assert latest is not None
    assert latest.source == "inlet"


def test_counting_is_per_board(unit):
    stream(unit, "bx-14", 60_000, 61_000, 62_000)
    stream(unit, "bx-15", 70_000)
    assert unit.thermal.count("bx-14") == 3
    assert unit.thermal.count() == 4


def test_pruning_drops_the_old_readings_and_says_how_many(unit):
    thermal_service.record(unit, "bx-14", WHEN - timedelta(days=9), 55_000)
    thermal_service.record(unit, "bx-14", WHEN - timedelta(days=8), 56_000)
    stream(unit, "bx-14", 60_000)
    assert thermal_service.prune(unit, WHEN) == 2
    assert unit.thermal.count("bx-14") == 1


def test_pruning_keeps_what_is_inside_the_window(unit):
    stream(unit, "bx-14", 60_000, 61_000)
    assert thermal_service.prune(unit, WHEN) == 0


def test_a_quiet_bench_produces_no_actions(unit):
    stream(unit, "bx-14", 60_000, 61_000, 62_000)
    stream(unit, "bx-15", 55_000, 55_500, 56_000)
    assert thermal_service.tick(unit, WHEN, alarms=AlarmBook(LIMITS)) == ()


def test_a_warm_board_is_noted_rather_than_touched(unit):
    stream(unit, "bx-14", 77_000, 78_000, 79_000)
    actions = thermal_service.tick(unit, WHEN, alarms=AlarmBook(LIMITS))
    assert [action.action for action in actions] == ["note"]
    assert unit.boards.state("bx-14").state == "available"


def test_a_critical_board_warns_and_names_what_is_downwind(unit):
    stream(unit, "bx-14", 87_000, 87_500, 88_000)
    actions = thermal_service.tick(unit, WHEN, alarms=AlarmBook(LIMITS))
    assert actions[0].action == "warn"


def test_a_board_over_the_shutdown_line_is_faulted(unit):
    stream(unit, "bx-14", 91_000, 91_500, 92_000)
    actions = thermal_service.tick(unit, WHEN, alarms=AlarmBook(LIMITS))
    assert actions[0].action == "shutdown"
    assert unit.boards.state("bx-14").state == "faulted"


def test_a_shutdown_gives_the_lease_back(unit):
    leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN - timedelta(minutes=30))
    stream(unit, "bx-14", 91_000, 91_500, 92_000)
    thermal_service.tick(unit, WHEN, alarms=AlarmBook(LIMITS))
    assert unit.leases.for_board("bx-14", WHEN) is None


def test_a_shutdown_leaves_a_line_in_the_audit_trail(unit):
    stream(unit, "bx-14", 91_000, 92_000)
    thermal_service.tick(unit, WHEN, alarms=AlarmBook(LIMITS))
    trail = unit.audit.for_subject("bx-14")
    assert "shutdown" in [entry.action for entry in trail]
    actions = {entry.action for entry in trail if entry.actor == "thermal"}
    assert actions == {"shutdown", "board-state"}


def test_a_note_is_not_worth_an_audit_line(unit):
    stream(unit, "bx-14", 77_000, 78_000)
    thermal_service.tick(unit, WHEN, alarms=AlarmBook(LIMITS))
    assert [entry for entry in unit.audit.for_subject("bx-14") if entry.actor == "thermal"] == []


def test_the_status_pass_changes_nothing(unit):
    stream(unit, "bx-14", 91_000, 92_000)
    statuses = thermal_service.status_of(unit, WHEN, settings=BenchSettings())
    assert statuses[0].board in ("bx-14", "bx-15")
    assert unit.boards.state("bx-14").state == "available"


def test_hysteresis_needs_the_same_book_between_ticks(unit):
    book = AlarmBook(LIMITS)
    stream(unit, "bx-14", 87_000, 88_000)
    thermal_service.tick(unit, WHEN, alarms=book)
    thermal_service.record(unit, "bx-14", WHEN + timedelta(seconds=20), 84_000)
    again = thermal_service.tick(unit, WHEN + timedelta(seconds=20), alarms=book)
    assert [action.action for action in again] == ["warn"]
