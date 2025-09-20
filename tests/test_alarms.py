from datetime import UTC, datetime, timedelta

import pytest

from fpga.errors import ValidationError
from fpga.temperature import ThermalLimits
from fpga.thermal.alarms import UNKNOWN, AlarmBook, ThermalStatus
from fpga.thermal.samples import ThermalLog

NOW = datetime(2025, 9, 18, 15, 0, tzinfo=UTC)


def limits():
    return ThermalLimits(warn_mc=76_000, critical_mc=86_000, shutdown_mc=90_000)


def book(**rest):
    return AlarmBook(limits(), **rest)


def log_at(board="bx-14", *readings, now=NOW, spacing=timedelta(seconds=20)):
    log = ThermalLog()
    moment = now - spacing * (len(readings) - 1)
    for value in readings:
        log.add(board, moment, value)
        moment += spacing
    return log


def test_a_cool_board_is_normal():
    status = book().update("bx-14", log_at("bx-14", 60_000, 61_000), NOW)
    assert status.state == "normal"
    assert status.healthy
    assert not status.alarming
    assert status.millidegrees == 60_500


def test_a_hot_board_warns():
    status = book().update("bx-14", log_at("bx-14", 77_000, 78_000), NOW)
    assert status.state == "warn"
    assert not status.alarming


def test_a_very_hot_board_is_critical_and_alarming():
    status = book().update("bx-14", log_at("bx-14", 87_000, 88_000), NOW)
    assert status.state == "critical"
    assert status.alarming


def test_the_average_decides_rather_than_one_spike():
    status = book().update("bx-14", log_at("bx-14", 60_000, 90_000, 60_000, 61_000), NOW)
    assert status.state == "normal"


def test_hysteresis_holds_a_board_in_its_state_on_the_way_down():
    live = book()
    log = ThermalLog()
    log.add("bx-14", NOW - timedelta(seconds=20), 88_000)
    log.add("bx-14", NOW, 88_000)
    assert live.update("bx-14", log, NOW).state == "critical"
    later = NOW + timedelta(seconds=40)
    log.add("bx-14", later, 84_000)
    assert live.update("bx-14", log, later).state == "critical"


def test_a_board_that_cools_properly_comes_back_down():
    live = book()
    log = ThermalLog()
    log.add("bx-14", NOW, 88_000)
    live.update("bx-14", log, NOW)
    later = NOW + timedelta(minutes=2)
    log.add("bx-14", later, 60_000)
    assert live.update("bx-14", log, later).state == "normal"


def test_the_since_time_only_moves_when_the_state_does():
    live = book()
    log = ThermalLog()
    log.add("bx-14", NOW, 60_000)
    first = live.update("bx-14", log, NOW)
    later = NOW + timedelta(seconds=40)
    log.add("bx-14", later, 61_000)
    second = live.update("bx-14", log, later)
    assert second.since == first.since
    assert second.held_for(later) == timedelta(seconds=40)


def test_a_board_that_stops_reporting_becomes_unknown():
    live = book()
    log = log_at("bx-14", 60_000, 61_000)
    live.update("bx-14", log, NOW)
    quiet = NOW + timedelta(minutes=10)
    status = live.update("bx-14", log, quiet)
    assert status.state == UNKNOWN
    assert status.stale
    assert status.alarming
    assert status.describe() == "bx-14 not reporting"


def test_a_board_that_never_reported_is_unknown_too():
    status = book().update("bx-14", ThermalLog(), NOW)
    assert status.state == UNKNOWN
    assert status.millidegrees is None


def test_a_board_that_starts_reporting_again_leaves_unknown():
    live = book()
    log = ThermalLog()
    live.update("bx-14", log, NOW)
    later = NOW + timedelta(minutes=1)
    log.add("bx-14", later, 62_000)
    assert live.update("bx-14", log, later).state == "normal"


def test_unknown_sorts_with_critical_rather_than_with_normal():
    live = book()
    live.update("bx-14", log_at("bx-14", 60_000), NOW)
    live.update("bx-15", ThermalLog(), NOW)
    worst = [status.board for status in live.worst_first()]
    assert worst[0] == "bx-15"
    assert [status.board for status in live.alarming()] == ["bx-15"]


def test_every_board_in_the_log_gets_updated():
    log = ThermalLog()
    log.add("bx-14", NOW, 60_000)
    log.add("bx-15", NOW, 88_000)
    live = book()
    statuses = live.update_all(log, NOW)
    assert [status.board for status in statuses] == ["bx-15", "bx-14"]
    assert len(live) == 2
    assert live.boards() == ("bx-14", "bx-15")


def test_boards_can_be_asked_for_by_state():
    log = ThermalLog()
    log.add("bx-14", NOW, 60_000)
    log.add("bx-15", NOW, 88_000)
    live = book()
    live.update_all(log, NOW)
    assert [status.board for status in live.in_state("critical")] == ["bx-15"]
    assert [status.board for status in live.in_state("normal")] == ["bx-14"]


def test_forgetting_a_board_stops_the_bench_watching_it():
    live = book()
    live.update("bx-14", log_at("bx-14", 60_000), NOW)
    live.forget("bx-14")
    assert live.status("bx-14") is None
    assert len(live) == 0


def test_a_status_for_a_state_nobody_has_is_refused():
    with pytest.raises(ValidationError):
        ThermalStatus(board="bx-14", state="toasty", since=NOW)


def test_an_alarm_book_with_no_window_is_refused():
    with pytest.raises(ValidationError):
        AlarmBook(limits(), window=timedelta(0))
    with pytest.raises(ValidationError):
        AlarmBook(limits(), stale_after=timedelta(0))


def test_a_status_describes_its_reading():
    status = book().update("bx-14", log_at("bx-14", 62_500), NOW)
    assert status.describe() == "bx-14 normal at 62.5C"
