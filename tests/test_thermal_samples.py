from datetime import UTC, datetime, timedelta

import pytest

from fpga.errors import ValidationError
from fpga.thermal.samples import ThermalLog, ThermalSample

NOW = datetime(2025, 9, 10, 12, 0, tzinfo=UTC)
MINUTE = timedelta(minutes=1)


def log_with(*readings, board="bx-14", spacing=timedelta(seconds=20)):
    log = ThermalLog()
    moment = NOW - spacing * len(readings)
    for value in readings:
        moment += spacing
        log.add(board, moment, value)
    return log


def test_a_sample_carries_its_board_and_reading():
    sample = ThermalSample(board="BX-0014", at=NOW, millidegrees=62_500)
    assert sample.board == "bx-14"
    assert sample.describe().startswith("bx-14 62.5C at ")


def test_a_reading_no_device_would_produce_is_refused():
    with pytest.raises(ValidationError):
        ThermalSample(board="bx-14", at=NOW, millidegrees=500_000)
    with pytest.raises(ValidationError):
        ThermalSample(board="bx-14", at=NOW, millidegrees=-70_000)


def test_sample_times_are_in_utc():
    with pytest.raises(ValidationError):
        ThermalSample(board="bx-14", at=datetime(2025, 9, 10, 12), millidegrees=60_000)


def test_the_latest_reading_comes_back():
    log = log_with(60_000, 61_000, 62_000)
    latest = log.latest("bx-14")
    assert latest is not None
    assert latest.millidegrees == 62_000
    assert log.latest("bx-15") is None


def test_a_window_only_holds_what_is_inside_it():
    log = log_with(60_000, 61_000, 62_000, spacing=MINUTE)
    assert len(log.window("bx-14", NOW, timedelta(minutes=1, seconds=30))) == 2
    assert len(log.window("bx-14", NOW, timedelta(hours=1))) == 3


def test_a_window_of_no_length_is_a_bug():
    with pytest.raises(ValidationError):
        ThermalLog().window("bx-14", NOW, timedelta(0))


def test_the_average_smooths_the_noise_out():
    log = log_with(60_000, 62_000, 61_000, 63_000)
    assert log.average("bx-14", NOW, timedelta(minutes=5)) == 61_500


def test_the_peak_is_the_worst_reading_in_the_window():
    log = log_with(60_000, 72_000, 61_000)
    assert log.peak("bx-14", NOW, timedelta(minutes=5)) == 72_000


def test_a_board_with_no_samples_averages_to_nothing():
    log = ThermalLog()
    assert log.average("bx-14", NOW, timedelta(minutes=5)) is None
    assert log.peak("bx-14", NOW, timedelta(minutes=5)) is None


def test_a_board_nobody_has_heard_from_is_stale():
    log = ThermalLog()
    assert log.stale("bx-14", NOW, timedelta(minutes=3))


def test_a_board_reporting_regularly_is_not_stale():
    log = log_with(60_000, 61_000)
    assert not log.stale("bx-14", NOW, timedelta(minutes=3))


def test_a_board_that_stopped_reporting_goes_stale():
    log = log_with(60_000, 61_000)
    assert log.stale("bx-14", NOW + timedelta(minutes=10), timedelta(minutes=3))


def test_a_rising_board_is_seen_rising():
    log = log_with(60_000, 61_000, 64_000, 66_000)
    assert log.rising("bx-14", NOW, timedelta(minutes=5)) is True


def test_a_cooling_board_is_seen_cooling():
    log = log_with(70_000, 68_000, 64_000, 61_000)
    assert log.rising("bx-14", NOW, timedelta(minutes=5)) is False


def test_a_trend_needs_enough_samples_to_mean_anything():
    log = log_with(60_000, 70_000)
    assert log.rising("bx-14", NOW, timedelta(minutes=5)) is None


def test_boards_are_kept_apart():
    log = ThermalLog()
    log.add("bx-14", NOW, 60_000)
    log.add("bx-15", NOW, 80_000)
    assert log.boards() == ("bx-14", "bx-15")
    assert log.latest("bx-15").millidegrees == 80_000
    assert len(log) == 2


def test_the_log_forgets_the_oldest_samples_once_it_is_full():
    log = ThermalLog(keep=3)
    for index in range(10):
        log.add("bx-14", NOW + timedelta(seconds=index), 60_000 + index)
    assert len(log) == 3
    assert log.latest("bx-14").millidegrees == 60_009


def test_a_log_that_keeps_nothing_is_refused():
    with pytest.raises(ValidationError):
        ThermalLog(keep=1)


def test_pruning_drops_the_old_ones_and_says_how_many():
    log = log_with(60_000, 61_000, 62_000, spacing=MINUTE)
    dropped = log.prune(NOW - timedelta(seconds=30))
    assert dropped == 2
    assert len(log) == 1


def test_pruning_everything_takes_the_board_off_the_list():
    log = log_with(60_000, 61_000)
    log.prune(NOW + timedelta(hours=1))
    assert log.boards() == ()
    assert len(log) == 0


def test_the_age_of_a_sample_never_goes_negative():
    sample = ThermalSample(board="bx-14", at=NOW, millidegrees=60_000)
    assert sample.age(NOW - timedelta(minutes=5)) == timedelta(0)
    assert sample.age(NOW + timedelta(minutes=5)) == timedelta(minutes=5)
