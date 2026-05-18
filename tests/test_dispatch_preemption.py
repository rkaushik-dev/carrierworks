from datetime import UTC, datetime, timedelta

import pytest

from boardsetup import artix, kintex
from fpga.persistence.unit import BenchDatabase
from fpga.scheduling.preemption import PreemptionPolicy
from fpga.services import inventory, leasing
from fpga.services import scheduling as queue_service

WHEN = datetime(2026, 5, 18, 11, 0, tzinfo=UTC)


@pytest.fixture()
def unit():
    database = BenchDatabase.in_memory()
    with database.unit() as open_unit:
        inventory.add_board(open_unit, kintex("bx-14"), WHEN - timedelta(days=1))
        inventory.add_board(open_unit, artix("bx-15"), WHEN - timedelta(days=1))
        yield open_unit


def holds(unit, board="bx-14", holder="team-a", priority=5, minutes=180, ago=60, **rest):
    """A lease somebody has been holding for a while."""
    return leasing.acquire(
        unit,
        board,
        holder,
        "long capture",
        WHEN - timedelta(minutes=ago),
        minutes=minutes,
        priority=priority,
        **rest,
    )


def ask(unit, priority=1, needs="device=xc7k325t", holder="team-b"):
    return queue_service.submit(unit, holder, "urgent capture", needs, WHEN, priority=priority)


def test_preemption_is_off_unless_it_is_asked_for(unit):
    holds(unit)
    request = ask(unit)
    outcome = queue_service.dispatch(unit, WHEN)
    assert outcome.preempted == ()
    assert request.id in outcome.refusals


def test_an_urgent_request_takes_a_board_off_a_low_priority_holder(unit):
    lease = holds(unit, priority=7)
    request = ask(unit, priority=1)
    outcome = queue_service.dispatch(unit, WHEN, preempt=True)
    assert outcome.preempted == ((lease.id, "bx-14"),)
    assert outcome.assigned[0][:2] == (request.id, "bx-14")


def test_the_bumped_holder_loses_the_lease(unit):
    lease = holds(unit, priority=7)
    ask(unit, priority=1)
    queue_service.dispatch(unit, WHEN, preempt=True)
    assert unit.leases.get(lease.id).released_at is not None
    held = unit.leases.for_board("bx-14", WHEN)
    assert held is not None
    assert held.holder == "team-b"


def test_the_release_says_which_request_took_it(unit):
    lease = holds(unit, priority=7)
    request = ask(unit, priority=1)
    queue_service.dispatch(unit, WHEN, preempt=True)
    assert unit.leases.get(lease.id).release_reason == f"preempted by {request.id}"


def test_a_lease_that_does_not_lose_by_enough_stays(unit):
    holds(unit, priority=5)
    request = ask(unit, priority=4)
    outcome = queue_service.dispatch(unit, WHEN, preempt=True)
    assert outcome.preempted == ()
    assert request.id in outcome.refusals


def test_a_lease_marked_unpreemptible_stays(unit):
    holds(unit, priority=9, preemptible=False)
    ask(unit, priority=1)
    assert queue_service.dispatch(unit, WHEN, preempt=True).preempted == ()


def test_a_lease_that_just_started_stays(unit):
    holds(unit, priority=9, ago=2)
    assert ask(unit, priority=1)
    assert queue_service.dispatch(unit, WHEN, preempt=True).preempted == ()


def test_a_lease_about_to_end_stays(unit):
    holds(unit, priority=9, minutes=62, ago=60)
    ask(unit, priority=1)
    assert queue_service.dispatch(unit, WHEN, preempt=True).preempted == ()


def test_a_board_being_programmed_is_not_taken(unit):
    holds(unit, priority=9)
    inventory.move_state(unit, "bx-14", "programming", WHEN, actor="team-a")
    ask(unit, priority=1)
    assert queue_service.dispatch(unit, WHEN, preempt=True).preempted == ()


def test_a_board_that_does_not_match_is_not_taken(unit):
    holds(unit, board="bx-15", priority=9)
    inventory.move_state(unit, "bx-14", "maintenance", WHEN, reason="fan", actor="ops")
    ask(unit, priority=1, needs="device=xc7k325t")
    assert queue_service.dispatch(unit, WHEN, preempt=True).preempted == ()


def test_a_free_board_is_always_preferred_to_taking_one(unit):
    holds(unit, board="bx-14", priority=9)
    ask(unit, priority=1, needs="")
    outcome = queue_service.dispatch(unit, WHEN, preempt=True)
    assert outcome.preempted == ()
    assert outcome.assigned[0][1] == "bx-15"


def test_the_cheapest_lease_is_the_one_taken(unit):
    inventory.add_board(
        unit,
        kintex("bx-16", location="rack-2/shelf-07/slot-1", serial="210308BB2222", outlet=4),
        WHEN - timedelta(days=1),
    )
    holds(unit, board="bx-14", holder="team-a", priority=6)
    cheap = holds(unit, board="bx-16", holder="team-c", priority=8)
    ask(unit, priority=1)
    outcome = queue_service.dispatch(unit, WHEN, preempt=True)
    assert outcome.preempted == ((cheap.id, "bx-16"),)


def test_a_tighter_margin_can_be_passed_in(unit):
    holds(unit, priority=5)
    ask(unit, priority=4)
    outcome = queue_service.dispatch(
        unit,
        WHEN,
        preempt=True,
        policy=PreemptionPolicy(margin=1),
    )
    assert outcome.preempted != ()


def test_the_outcome_says_what_it_took(unit):
    holds(unit, priority=7)
    ask(unit, priority=1)
    assert (
        "took lease-00001 off bx-14" in queue_service.dispatch(unit, WHEN, preempt=True).describe()
    )
