from datetime import UTC, datetime, timedelta

import pytest

from fpga.capabilities import RequirementSet
from fpga.errors import ValidationError
from fpga.leases.models import Lease
from fpga.scheduling.metrics import (
    busy_seconds,
    holder_hours,
    idle_boards,
    utilisation,
    wait_stats,
)
from fpga.scheduling.queue import RequestQueue

START = datetime(2025, 8, 25, 0, 0, tzinfo=UTC)
END = START + timedelta(hours=24)


def lease(board="bx-14", holder="team-a", start_hour=0, hours=1, released=True):
    taken = START + timedelta(hours=start_hour)
    expires = taken + timedelta(hours=hours)
    live = Lease(
        id=f"lease-{board}-{start_hour}",
        board=board,
        holder=holder,
        purpose="capture",
        taken_at=taken,
        expires_at=expires,
    )
    return live.release(expires, "finished") if released else live


def test_busy_time_adds_up_per_board():
    leases = (lease(hours=2), lease(start_hour=6, hours=3), lease(board="bx-15", hours=1))
    busy = busy_seconds(leases, START, END)
    assert busy["bx-14"] == 5 * 3600
    assert busy["bx-15"] == 3600


def test_a_lease_that_started_before_the_window_is_clipped():
    early = Lease(
        id="lease-1",
        board="bx-14",
        holder="team-a",
        purpose="capture",
        taken_at=START - timedelta(hours=4),
        expires_at=START + timedelta(hours=2),
    )
    assert busy_seconds((early,), START, END)["bx-14"] == 2 * 3600


def test_a_lease_that_runs_past_the_window_is_clipped_too():
    late = lease(start_hour=23, hours=5, released=False)
    assert busy_seconds((late,), START, END)["bx-14"] == 3600


def test_a_lease_wholly_outside_the_window_counts_for_nothing():
    outside = lease(start_hour=30, hours=1)
    assert busy_seconds((outside,), START, END) == {}


def test_utilisation_includes_the_boards_nobody_used():
    leases = (lease(hours=6),)
    numbers = utilisation(leases, ("bx-14", "bx-15"), START, END)
    assert numbers["bx-14"] == pytest.approx(0.25)
    assert numbers["bx-15"] == 0.0


def test_utilisation_never_goes_over_one():
    leases = (lease(hours=12), lease(start_hour=6, hours=12))
    assert utilisation(leases, ("bx-14",), START, END)["bx-14"] == 1.0


def test_a_window_that_ends_before_it_starts_is_a_bug():
    with pytest.raises(ValidationError):
        utilisation((), ("bx-14",), END, START)


def test_board_hours_by_holder():
    leases = (
        lease(holder="team-a", hours=2),
        lease(holder="team-a", board="bx-15", hours=1),
        lease(holder="nightly", start_hour=4, hours=3),
    )
    assert holder_hours(leases, START, END) == {"team-a": 3.0, "nightly": 3.0}


def test_holder_hours_are_rounded_for_reading():
    odd = lease(hours=1)
    hours = holder_hours((odd,), START, START + timedelta(hours=2))
    assert hours == {"team-a": 1.0}


def test_the_idle_boards_are_the_ones_nobody_touched():
    leases = (lease(board="bx-14"),)
    assert idle_boards(leases, ("bx-14", "bx-15", "bx-16"), START, END) == ("bx-15", "bx-16")


def test_an_empty_queue_has_no_waits():
    stats = wait_stats(RequestQueue(), START)
    assert stats.empty
    assert stats.describe() == "nothing waiting"


def test_wait_stats_over_an_odd_number_of_requests():
    queue = RequestQueue()
    wanted = RequirementSet.parse([])
    queue.submit("team-a", "one", wanted, START)
    queue.submit("team-b", "two", wanted, START + timedelta(minutes=10))
    queue.submit("team-c", "three", wanted, START + timedelta(minutes=30))
    stats = wait_stats(queue, START + timedelta(minutes=40))
    assert stats.waiting == 3
    assert stats.longest == timedelta(minutes=40)
    assert stats.median == timedelta(minutes=30)


def test_wait_stats_over_an_even_number_takes_the_middle_pair():
    queue = RequestQueue()
    wanted = RequirementSet.parse([])
    queue.submit("team-a", "one", wanted, START)
    queue.submit("team-b", "two", wanted, START + timedelta(minutes=20))
    stats = wait_stats(queue, START + timedelta(minutes=40))
    assert stats.median == timedelta(minutes=30)


def test_satisfied_requests_leave_the_wait_stats():
    queue = RequestQueue()
    wanted = RequirementSet.parse([])
    request = queue.submit("team-a", "one", wanted, START)
    queue.satisfy(request.id, START + timedelta(minutes=5))
    assert wait_stats(queue, START + timedelta(minutes=40)).empty


def test_wait_stats_describe_themselves_in_minutes():
    queue = RequestQueue()
    queue.submit("team-a", "one", RequirementSet.parse([]), START)
    described = wait_stats(queue, START + timedelta(minutes=12)).describe()
    assert described == "1 waiting, longest 12m, median 12m"
