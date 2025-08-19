from datetime import UTC, datetime, timedelta

import pytest

from fpga.errors import ValidationError
from fpga.leases.models import Lease
from fpga.scheduling.preemption import PreemptionPolicy

NOW = datetime(2025, 8, 18, 14, 0, tzinfo=UTC)


def lease(**changes):
    fields = {
        "id": "lease-00001",
        "board": "bx-14",
        "holder": "team-a",
        "purpose": "experiment",
        "taken_at": NOW - timedelta(minutes=30),
        "expires_at": NOW + timedelta(minutes=30),
        "priority": 6,
    }
    fields.update(changes)
    return Lease(**fields)


def policy(**changes):
    return PreemptionPolicy(**changes)


def test_a_clear_case_is_allowed():
    decision = policy().evaluate(lease(), NOW, claimant_priority=2)
    assert decision.allowed
    assert decision.describe() == "lease-00001 on bx-14 may be taken (preemption)"


def test_an_unpreemptible_lease_stays():
    decision = policy().evaluate(lease(preemptible=False), NOW, claimant_priority=1)
    assert not decision.allowed
    assert "unpreemptible" in decision.blockers[0]


def test_the_claimant_has_to_beat_the_holder_by_the_margin():
    live = lease(priority=5)
    assert not policy().evaluate(live, NOW, claimant_priority=4).allowed
    assert policy().evaluate(live, NOW, claimant_priority=3).allowed


def test_the_margin_is_settable():
    live = lease(priority=5)
    assert policy(margin=1).evaluate(live, NOW, claimant_priority=4).allowed


def test_a_margin_of_nothing_is_refused():
    with pytest.raises(ValidationError):
        PreemptionPolicy(margin=0)
    with pytest.raises(ValidationError):
        PreemptionPolicy(minimum_held=timedelta(minutes=-1))


def test_a_lease_that_has_only_just_started_is_left_alone():
    fresh = lease(taken_at=NOW - timedelta(minutes=2))
    decision = policy().evaluate(fresh, NOW, claimant_priority=1)
    assert not decision.allowed
    assert "2 minutes old" in decision.blockers[0]


def test_a_lease_that_is_nearly_over_is_left_alone():
    ending = lease(expires_at=NOW + timedelta(minutes=3))
    decision = policy().evaluate(ending, NOW, claimant_priority=1)
    assert "3 minutes left" in decision.blockers[0]


def test_a_board_being_programmed_is_never_taken():
    decision = policy().evaluate(
        lease(),
        NOW,
        claimant_priority=1,
        board_state="programming",
    )
    assert not decision.allowed
    assert decision.blockers[0] == "the board is being programmed"


def test_an_emergency_takes_the_board_whatever_the_rules_say():
    decision = policy().evaluate(
        lease(preemptible=False, priority=1, taken_at=NOW),
        NOW,
        claimant_priority=9,
        board_state="programming",
        emergency=True,
    )
    assert decision.allowed
    assert decision.emergency
    assert "emergency" in decision.describe()


def test_a_lease_that_has_already_ended_cannot_be_preempted():
    over = lease(expires_at=NOW - timedelta(minutes=1))
    decision = policy().evaluate(over, NOW, claimant_priority=1)
    assert decision.blockers == ("that lease is not held any more",)


def test_every_reason_is_reported_not_just_the_first():
    stubborn = lease(preemptible=False, priority=4, taken_at=NOW - timedelta(minutes=1))
    decision = policy().evaluate(stubborn, NOW, claimant_priority=5)
    assert len(decision.blockers) == 3


def test_choosing_takes_the_least_important_holder():
    leases = (
        lease(id="lease-00001", board="bx-14", priority=6),
        lease(id="lease-00002", board="bx-15", priority=8),
    )
    picked = policy().choose(leases, NOW, claimant_priority=2)
    assert picked is not None
    assert picked.id == "lease-00002"


def test_choosing_throws_away_the_least_work():
    leases = (
        lease(id="lease-00001", board="bx-14", taken_at=NOW - timedelta(minutes=50)),
        lease(id="lease-00002", board="bx-15", taken_at=NOW - timedelta(minutes=10)),
    )
    picked = policy().choose(leases, NOW, claimant_priority=2)
    assert picked is not None
    assert picked.id == "lease-00002"


def test_choosing_skips_the_board_that_is_being_programmed():
    leases = (
        lease(id="lease-00001", board="bx-14", priority=8),
        lease(id="lease-00002", board="bx-15", priority=7),
    )
    picked = policy().choose(
        leases,
        NOW,
        claimant_priority=2,
        board_states={"bx-14": "programming"},
    )
    assert picked is not None
    assert picked.id == "lease-00002"


def test_choosing_nothing_when_nothing_can_be_taken():
    leases = (lease(preemptible=False),)
    assert policy().choose(leases, NOW, claimant_priority=1) is None
    assert policy().choose((), NOW, claimant_priority=1) is None
