from datetime import UTC, date, datetime, timedelta

import pytest

from fpga.benchcalendar import BenchCalendar, MaintenanceWindow, ServiceHours
from fpga.errors import ValidationError


def utc(year, month, day, hour=0, minute=0):
    return datetime(year, month, day, hour, minute, tzinfo=UTC)


def test_service_hours_parse():
    hours = ServiceHours.parse("08:00-18:30")
    assert hours.opens == 480
    assert hours.closes == 1110
    assert hours.covers(480)
    assert not hours.covers(1110)


@pytest.mark.parametrize("text", ["", "08:00", "18:00-08:00", "8-18", "08:70-18:00", "aa:bb-cc:dd"])
def test_bad_service_hours_are_refused(text):
    with pytest.raises(ValidationError):
        ServiceHours.parse(text)


def test_the_bench_is_staffed_during_the_local_working_day():
    calendar = BenchCalendar.weekdays()
    # 2025-03-05 was a wednesday. 14:00 utc is 09:00 local.
    assert calendar.is_staffed(utc(2025, 3, 5, 14, 0))
    assert not calendar.is_staffed(utc(2025, 3, 5, 4, 0))


def test_the_offset_is_applied_rather_than_ignored():
    calendar = BenchCalendar.weekdays()
    # 02:00 utc on thursday is 21:00 wednesday local, which is after hours.
    assert not calendar.is_staffed(utc(2025, 3, 6, 2, 0))
    assert calendar.is_staffed(utc(2025, 3, 6, 13, 30))


def test_weekends_are_not_staffed():
    calendar = BenchCalendar.weekdays()
    assert not calendar.is_staffed(utc(2025, 3, 8, 15, 0))
    assert not calendar.is_staffed(utc(2025, 3, 9, 15, 0))


def test_holidays_close_the_lab():
    calendar = BenchCalendar.weekdays(holidays=frozenset({date(2025, 5, 26)}))
    assert not calendar.is_staffed(utc(2025, 5, 26, 15, 0))
    assert calendar.is_staffed(utc(2025, 5, 27, 15, 0))


def test_a_naive_timestamp_is_refused():
    with pytest.raises(ValidationError):
        BenchCalendar.weekdays().is_staffed(datetime(2025, 3, 5, 14, 0))


def test_a_maintenance_window_covers_its_own_stretch():
    window = MaintenanceWindow(
        start=utc(2025, 3, 5, 6, 0),
        end=utc(2025, 3, 5, 10, 0),
        reason="chilled water loop down",
    )
    assert window.covers(utc(2025, 3, 5, 6, 0))
    assert window.covers(utc(2025, 3, 5, 9, 59))
    assert not window.covers(utc(2025, 3, 5, 10, 0))
    assert window.minutes == 240


def test_a_window_needs_a_positive_length_and_a_timezone():
    with pytest.raises(ValidationError):
        MaintenanceWindow(start=utc(2025, 3, 5, 10), end=utc(2025, 3, 5, 6), reason="x")
    with pytest.raises(ValidationError):
        MaintenanceWindow(
            start=datetime(2025, 3, 5, 6),
            end=datetime(2025, 3, 5, 10),
            reason="x",
        )


def test_a_bench_window_covers_every_rack_and_a_rack_window_covers_one():
    bench = MaintenanceWindow(start=utc(2025, 3, 5, 6), end=utc(2025, 3, 5, 10), reason="x")
    rack = MaintenanceWindow(
        start=utc(2025, 3, 5, 6),
        end=utc(2025, 3, 5, 10),
        reason="pdu swap",
        scope="rack-2",
    )
    assert bench.applies_to(None)
    assert bench.applies_to("rack-4")
    assert rack.applies_to("rack-2")
    assert not rack.applies_to("rack-4")
    assert not rack.applies_to(None)


def test_windows_know_when_they_overlap():
    first = MaintenanceWindow(start=utc(2025, 3, 5, 6), end=utc(2025, 3, 5, 10), reason="a")
    touching = MaintenanceWindow(start=utc(2025, 3, 5, 10), end=utc(2025, 3, 5, 12), reason="b")
    overlapping = MaintenanceWindow(start=utc(2025, 3, 5, 9), end=utc(2025, 3, 5, 12), reason="c")
    assert not first.overlaps(touching)
    assert first.overlaps(overlapping)
    assert overlapping.overlaps(first)


def test_maintenance_blocks_work_even_in_staffed_hours():
    window = MaintenanceWindow(
        start=utc(2025, 3, 5, 14),
        end=utc(2025, 3, 5, 16),
        reason="chilled water loop down",
    )
    calendar = BenchCalendar.weekdays().with_window(window)
    assert calendar.blocked(utc(2025, 3, 5, 15)) == "maintenance: chilled water loop down"
    assert calendar.blocked(utc(2025, 3, 5, 17)) is None


def test_a_rack_window_leaves_the_other_racks_alone():
    window = MaintenanceWindow(
        start=utc(2025, 3, 5, 14),
        end=utc(2025, 3, 5, 16),
        reason="pdu swap",
        scope="rack-2",
    )
    calendar = BenchCalendar.weekdays().with_window(window)
    assert calendar.blocked(utc(2025, 3, 5, 15), "rack-2") is not None
    assert calendar.blocked(utc(2025, 3, 5, 15), "rack-4") is None


def test_out_of_hours_is_reported_as_such():
    assert BenchCalendar.weekdays().blocked(utc(2025, 3, 5, 4)) == "outside staffed hours"


def test_two_overlapping_windows_on_one_scope_are_refused():
    calendar = BenchCalendar.weekdays().with_window(
        MaintenanceWindow(start=utc(2025, 3, 5, 14), end=utc(2025, 3, 5, 16), reason="a")
    )
    with pytest.raises(ValidationError):
        calendar.with_window(
            MaintenanceWindow(start=utc(2025, 3, 5, 15), end=utc(2025, 3, 5, 17), reason="b")
        )


def test_windows_on_different_scopes_may_overlap():
    calendar = BenchCalendar.weekdays().with_window(
        MaintenanceWindow(
            start=utc(2025, 3, 5, 14),
            end=utc(2025, 3, 5, 16),
            reason="a",
            scope="rack-2",
        )
    )
    extended = calendar.with_window(
        MaintenanceWindow(
            start=utc(2025, 3, 5, 15),
            end=utc(2025, 3, 5, 17),
            reason="b",
            scope="rack-4",
        )
    )
    assert len(extended.windows) == 2


def test_the_next_staffed_minute_is_the_start_of_the_next_working_day():
    calendar = BenchCalendar.weekdays()
    found = calendar.next_staffed(utc(2025, 3, 8, 15, 0))
    assert found == utc(2025, 3, 10, 13, 0)


def test_asking_while_staffed_gives_now():
    calendar = BenchCalendar.weekdays()
    now = utc(2025, 3, 5, 14, 30)
    assert calendar.next_staffed(now) == now


def test_maintenance_pushes_the_next_staffed_minute_out():
    window = MaintenanceWindow(
        start=utc(2025, 3, 5, 13),
        end=utc(2025, 3, 5, 15),
        reason="hvac",
    )
    calendar = BenchCalendar.weekdays().with_window(window)
    assert calendar.next_staffed(utc(2025, 3, 5, 14)) == utc(2025, 3, 5, 15)


def test_a_bench_with_no_hours_never_opens():
    calendar = BenchCalendar()
    assert calendar.next_staffed(utc(2025, 3, 5, 14), horizon_days=1) is None


def test_the_horizon_is_respected():
    calendar = BenchCalendar.weekdays()
    friday_evening = utc(2025, 3, 7, 23, 30)
    assert calendar.next_staffed(friday_evening, horizon_days=1) is None
    assert calendar.next_staffed(friday_evening, horizon_days=3) == utc(2025, 3, 10, 13, 0)


def test_the_search_starts_from_a_whole_minute():
    calendar = BenchCalendar.weekdays()
    ragged = utc(2025, 3, 5, 14, 30) + timedelta(seconds=45)
    assert calendar.next_staffed(ragged) == utc(2025, 3, 5, 14, 30)
