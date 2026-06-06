from datetime import UTC, datetime, timedelta

import pytest

from boardsetup import artix, kintex
from fpga.errors import NotFound, ValidationError
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory
from fpga.services import thermal as thermal_service
from fpga.thermal.samples import ThermalSample

WHEN = datetime(2026, 6, 12, 7, 0, tzinfo=UTC)


@pytest.fixture()
def unit():
    database = BenchDatabase.in_memory()
    with database.unit() as open_unit:
        inventory.add_board(open_unit, kintex("bx-14"), WHEN - timedelta(days=1))
        inventory.add_board(open_unit, artix("bx-15"), WHEN - timedelta(days=1))
        yield open_unit


def poll(*boards, at=WHEN, base=60_000, source="sysmon"):
    """What one controller posts after reading its boards."""
    return [(tag, at, base + index * 500, source) for index, tag in enumerate(boards)]


def test_a_batch_is_stored(unit):
    stored = thermal_service.record_batch(unit, poll("bx-14", "bx-15"))
    assert [sample.board for sample in stored] == ["bx-14", "bx-15"]
    assert unit.thermal.count() == 2


def test_the_readings_come_back_with_their_values(unit):
    thermal_service.record_batch(unit, poll("bx-14", "bx-15"))
    latest = unit.thermal.latest("bx-15")
    assert latest is not None
    assert latest.millidegrees == 60_500


def test_the_source_is_kept(unit):
    thermal_service.record_batch(unit, poll("bx-14", source="inlet"))
    latest = unit.thermal.latest("bx-14")
    assert latest is not None
    assert latest.source == "inlet"


def test_an_empty_batch_stores_nothing(unit):
    assert thermal_service.record_batch(unit, []) == ()
    assert unit.thermal.count() == 0


def test_a_batch_naming_a_board_nobody_has_is_refused(unit):
    with pytest.raises(NotFound):
        thermal_service.record_batch(unit, poll("bx-14", "bx-99"))


def test_a_refused_batch_stores_none_of_it(unit):
    with pytest.raises(NotFound):
        thermal_service.record_batch(unit, poll("bx-14", "bx-99"))
    assert unit.thermal.count() == 0


def test_a_reading_off_the_scale_is_refused_before_anything_is_written(unit):
    with pytest.raises(ValidationError):
        thermal_service.record_batch(unit, [("bx-14", WHEN, 900_000, "sysmon")])
    assert unit.thermal.count() == 0


def test_a_batch_and_single_records_agree(unit):
    thermal_service.record_batch(unit, [("bx-14", WHEN, 64_500, "sysmon")])
    thermal_service.record(unit, "bx-15", WHEN, 64_500)
    assert unit.thermal.latest("bx-14") == ThermalSample(
        board="bx-14", at=WHEN, millidegrees=64_500
    )
    assert unit.thermal.latest("bx-15") == ThermalSample(
        board="bx-15", at=WHEN, millidegrees=64_500
    )


def test_a_whole_rack_poll_lands_in_one_call(unit):
    readings = []
    for minute in range(20):
        moment = WHEN - timedelta(seconds=20 * minute)
        readings += poll("bx-14", "bx-15", at=moment)
    assert len(thermal_service.record_batch(unit, readings)) == 40
    assert unit.thermal.count("bx-14") == 20


def test_the_repository_counts_what_it_wrote(unit):
    samples = [ThermalSample(board="bx-14", at=WHEN, millidegrees=61_000)]
    assert unit.thermal.record_many(samples) == 1
    assert unit.thermal.record_many([]) == 0


def test_the_window_reads_a_batch_back(unit):
    thermal_service.record_batch(unit, poll("bx-14", "bx-15"))
    log = thermal_service.load_window(unit, WHEN, timedelta(minutes=15))
    assert set(log.boards()) == {"bx-14", "bx-15"}
