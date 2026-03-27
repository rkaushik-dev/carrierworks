from datetime import UTC, datetime, timedelta

import pytest

from boardsetup import artix, kintex
from fpga.errors import NotFound, ValidationError
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory, leasing
from fpga.services import scheduling as queue_service

WHEN = datetime(2026, 3, 25, 8, 0, tzinfo=UTC)


@pytest.fixture()
def unit():
    database = BenchDatabase.in_memory()
    with database.unit() as open_unit:
        inventory.add_board(open_unit, kintex("bx-14"), WHEN - timedelta(days=1))
        inventory.add_board(open_unit, artix("bx-15"), WHEN - timedelta(days=1))
        yield open_unit


def ask(unit, holder="team-a", wants="device=xc7k325t", when=None, **rest):
    return queue_service.submit(
        unit,
        holder,
        rest.pop("purpose", "capture run"),
        wants,
        when or WHEN,
        **rest,
    )


def test_a_request_is_stored_with_the_words_it_was_written_in(unit):
    request = ask(unit, wants="card=fmc-adc-4ch-14b, transceivers>=4")
    read_back = unit.requests.get(request.id)
    assert [item.text for item in read_back.requirements] == [
        "card=fmc-adc-4ch-14b",
        "transceivers>=4",
    ]


def test_request_numbers_carry_on_from_what_is_stored(unit):
    assert ask(unit).id == "req-0001"
    assert ask(unit).id == "req-0002"


def test_a_request_nobody_made_is_not_found(unit):
    with pytest.raises(NotFound):
        unit.requests.get("req-9999")


def test_a_priority_outside_the_range_is_refused(unit):
    with pytest.raises(ValidationError):
        ask(unit, priority=11)


def test_an_open_request_is_waiting(unit):
    request = ask(unit)
    assert [entry.id for entry in queue_service.waiting(unit, WHEN)] == [request.id]


def test_a_cancelled_request_stops_waiting(unit):
    request = ask(unit)
    queue_service.cancel(unit, request.id, WHEN + timedelta(minutes=1))
    assert queue_service.waiting(unit, WHEN) == ()
    assert unit.requests.get(request.id).open is False


def test_cancelling_twice_is_refused(unit):
    request = ask(unit)
    queue_service.cancel(unit, request.id, WHEN + timedelta(minutes=1))
    with pytest.raises(ValidationError):
        queue_service.cancel(unit, request.id, WHEN + timedelta(minutes=2))


def test_higher_priority_is_served_first(unit):
    ask(unit, holder="team-a", priority=6)
    urgent = ask(unit, holder="team-b", priority=2)
    assert queue_service.waiting(unit, WHEN)[0].id == urgent.id


def test_at_the_same_priority_the_longer_wait_wins(unit):
    early = ask(unit, holder="team-a", when=WHEN - timedelta(hours=2))
    ask(unit, holder="team-b")
    assert queue_service.waiting(unit, WHEN)[0].id == early.id


def test_a_request_for_something_on_the_bench_gets_a_board(unit):
    request = ask(unit, wants="device=xc7k325t")
    outcome = queue_service.dispatch(unit, WHEN)
    assert outcome.assigned[0][0] == request.id
    assert outcome.assigned[0][1] == "bx-14"


def test_a_served_request_leaves_a_lease_behind(unit):
    ask(unit)
    outcome = queue_service.dispatch(unit, WHEN)
    held = unit.leases.for_board("bx-14", WHEN)
    assert held is not None
    assert held.id == outcome.assigned[0][2]
    assert held.holder == "team-a"


def test_a_served_request_records_which_board_it_got(unit):
    request = ask(unit)
    queue_service.dispatch(unit, WHEN)
    assert unit.requests.board_for(request.id) == "bx-14"
    assert unit.requests.get(request.id).open is False


def test_a_request_for_nothing_on_the_bench_waits(unit):
    request = ask(unit, wants="device=xcvu9p")
    outcome = queue_service.dispatch(unit, WHEN)
    assert outcome.assigned == ()
    assert outcome.refusals[request.id] == "nothing free matches what it asked for"
    assert unit.requests.get(request.id).open is True


def test_two_requests_never_get_the_same_board(unit):
    ask(unit, holder="team-a", wants="device=xc7k325t")
    second = ask(unit, holder="team-b", wants="device=xc7k325t")
    outcome = queue_service.dispatch(unit, WHEN)
    assert len(outcome.assigned) == 1
    assert second.id in outcome.refusals


def test_two_requests_for_different_parts_are_both_served(unit):
    ask(unit, holder="team-a", wants="device=xc7k325t")
    ask(unit, holder="team-b", wants="device=xc7a100t")
    outcome = queue_service.dispatch(unit, WHEN)
    assert {board for _, board, _ in outcome.assigned} == {"bx-14", "bx-15"}


def test_a_holder_at_its_quota_is_skipped_rather_than_refused(unit):
    leasing.acquire(unit, "bx-15", "team-a", "earlier run", WHEN - timedelta(minutes=10))
    request = ask(unit, holder="team-a")
    outcome = queue_service.dispatch(unit, WHEN, quotas={"team-a": 1})
    assert "quota is 1" in outcome.refusals[request.id]
    assert unit.requests.get(request.id).open is True


def test_a_holder_under_its_quota_is_served(unit):
    request = ask(unit, holder="team-a")
    outcome = queue_service.dispatch(unit, WHEN, quotas={"team-a": 2})
    assert outcome.assigned[0][0] == request.id


def test_a_dispatch_can_be_limited_to_one_board(unit):
    ask(unit, holder="team-a", wants="device=xc7k325t")
    ask(unit, holder="team-b", wants="device=xc7a100t")
    outcome = queue_service.dispatch(unit, WHEN, limit=1)
    assert len(outcome.assigned) == 1
    assert len(outcome.refusals) == 1


def test_a_negative_limit_is_refused(unit):
    with pytest.raises(ValidationError):
        queue_service.dispatch(unit, WHEN, limit=-1)


def test_a_board_out_of_service_is_not_handed_out(unit):
    inventory.move_state(unit, "bx-14", "maintenance", WHEN, reason="fan", actor="ops")
    request = ask(unit, wants="device=xc7k325t")
    outcome = queue_service.dispatch(unit, WHEN)
    assert request.id in outcome.refusals


def test_an_expired_lease_is_swept_before_the_pass(unit):
    leasing.acquire(unit, "bx-14", "team-b", "old run", WHEN - timedelta(hours=3), minutes=30)
    request = ask(unit, wants="device=xc7k325t")
    outcome = queue_service.dispatch(unit, WHEN)
    assert outcome.assigned[0][0] == request.id


def test_an_empty_queue_says_so(unit):
    assert queue_service.dispatch(unit, WHEN).describe() == "nothing waiting"


def test_the_outcome_reads_as_one_line(unit):
    ask(unit)
    assert "req-0001 -> bx-14" in queue_service.dispatch(unit, WHEN).describe()
