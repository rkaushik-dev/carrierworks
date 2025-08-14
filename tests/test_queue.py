from datetime import UTC, datetime, timedelta

import pytest

from fpga.capabilities import RequirementSet
from fpga.errors import NotFound, ValidationError
from fpga.scheduling.queue import RequestQueue

NOW = datetime(2025, 8, 11, 9, 0, tzinfo=UTC)


def wanted(*requirements):
    return RequirementSet.parse(list(requirements))


def queue():
    return RequestQueue()


def test_a_request_goes_on_the_queue():
    live = queue()
    request = live.submit("signal-processing", "capture run", wanted("adc-14bit"), NOW)
    assert request.id == "req-00001"
    assert request.holder == "signal-processing"
    assert request.open
    assert live.pending() == (request,)


def test_priority_comes_first():
    live = queue()
    live.submit("team-a", "experiment", wanted(), NOW, priority=7)
    urgent = live.submit(
        "team-b", "release check", wanted(), NOW + timedelta(minutes=1), priority=2
    )
    assert live.head().id == urgent.id


def test_fair_share_breaks_a_priority_tie():
    live = queue()
    hog = live.submit("nightly", "regression", wanted(), NOW)
    person = live.submit("dana", "bring up", wanted(), NOW + timedelta(minutes=5))
    ordered = live.ordered({"nightly": 4, "dana": 0})
    assert [request.id for request in ordered] == [person.id, hog.id]


def test_waiting_time_breaks_the_remaining_tie():
    live = queue()
    first = live.submit("team-a", "one", wanted(), NOW)
    second = live.submit("team-b", "two", wanted(), NOW + timedelta(minutes=1))
    assert [request.id for request in live.ordered()] == [first.id, second.id]


def test_holdings_are_read_by_holder_name_however_it_was_written():
    live = queue()
    hog = live.submit("Signal Processing", "regression", wanted(), NOW)
    other = live.submit("firmware", "bring up", wanted(), NOW + timedelta(minutes=5))
    ordered = live.ordered({"signal processing": 3})
    assert [request.id for request in ordered] == [other.id, hog.id]


def test_a_cancelled_request_leaves_the_queue():
    live = queue()
    request = live.submit("team-a", "one", wanted(), NOW)
    live.cancel(request.id, NOW + timedelta(minutes=2))
    assert live.pending() == ()
    assert not live.request(request.id).open
    assert live.head() is None


def test_a_satisfied_request_leaves_the_queue_too():
    live = queue()
    request = live.submit("team-a", "one", wanted(), NOW)
    live.satisfy(request.id, NOW + timedelta(minutes=3))
    assert live.pending() == ()
    assert live.request(request.id).satisfied_at == NOW + timedelta(minutes=3)


def test_a_closed_request_cannot_be_closed_again():
    live = queue()
    request = live.submit("team-a", "one", wanted(), NOW)
    live.satisfy(request.id, NOW + timedelta(minutes=1))
    with pytest.raises(ValidationError):
        live.cancel(request.id, NOW + timedelta(minutes=2))
    with pytest.raises(ValidationError):
        live.satisfy(request.id, NOW + timedelta(minutes=2))


def test_a_request_nobody_submitted_is_a_not_found():
    with pytest.raises(NotFound):
        queue().request("req-09999")


def test_position_counts_from_one_and_zero_means_not_waiting():
    live = queue()
    first = live.submit("team-a", "one", wanted(), NOW)
    second = live.submit("team-b", "two", wanted(), NOW + timedelta(minutes=1))
    assert live.position_of(first.id) == 1
    assert live.position_of(second.id) == 2
    live.satisfy(first.id, NOW + timedelta(minutes=2))
    assert live.position_of(first.id) == 0
    assert live.position_of(second.id) == 1


def test_position_moves_with_fair_share():
    live = queue()
    hog = live.submit("nightly", "regression", wanted(), NOW)
    live.submit("dana", "bring up", wanted(), NOW + timedelta(minutes=1))
    assert live.position_of(hog.id) == 1
    assert live.position_of(hog.id, {"nightly": 2}) == 2


def test_how_long_a_request_has_waited():
    live = queue()
    request = live.submit("team-a", "one", wanted(), NOW)
    assert request.waited(NOW + timedelta(minutes=20)) == timedelta(minutes=20)
    done = live.satisfy(request.id, NOW + timedelta(minutes=5))
    assert done.waited(NOW + timedelta(hours=2)) == timedelta(minutes=5)


def test_the_longest_wait_on_the_queue():
    live = queue()
    live.submit("team-a", "one", wanted(), NOW)
    live.submit("team-b", "two", wanted(), NOW + timedelta(minutes=10))
    assert live.longest_wait(NOW + timedelta(minutes=30)) == timedelta(minutes=30)
    assert RequestQueue().longest_wait(NOW) == timedelta(0)


def test_what_one_team_is_waiting_on():
    live = queue()
    live.submit("team-a", "one", wanted(), NOW)
    live.submit("team-a", "two", wanted(), NOW + timedelta(minutes=1))
    live.submit("team-b", "three", wanted(), NOW + timedelta(minutes=2))
    assert len(live.waiting_for("team-a")) == 2
    assert len(live.waiting_for("team-c")) == 0


def test_a_request_keeps_what_it_asked_for():
    live = queue()
    request = live.submit(
        "team-a",
        "capture",
        wanted("adc-14bit", "transceivers>=4"),
        NOW,
        minutes=120,
        needs_hands=True,
        preemptible=False,
    )
    assert len(request.requirements) == 2
    assert request.minutes == 120
    assert request.needs_hands
    assert not request.preemptible


@pytest.mark.parametrize(("priority", "minutes"), [(0, 60), (10, 60), (5, 0)])
def test_a_nonsense_request_is_refused(priority, minutes):
    with pytest.raises(ValidationError):
        queue().submit("team-a", "one", wanted(), NOW, priority=priority, minutes=minutes)


def test_a_request_without_a_purpose_is_refused():
    with pytest.raises(ValidationError):
        queue().submit("team-a", "  ", wanted(), NOW)


def test_a_request_describes_itself():
    request = queue().submit("team-a", "capture run", wanted(), NOW, priority=3)
    assert request.describe() == "req-00001 team-a priority 3 for capture run"
