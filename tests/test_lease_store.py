from datetime import UTC, datetime, timedelta

import pytest

from fpga.errors import Conflict, NotFound, ValidationError
from fpga.leases.store import LeaseStore

NOW = datetime(2025, 8, 4, 9, 0, tzinfo=UTC)


def store():
    return LeaseStore()


def test_taking_a_board_gives_a_lease():
    live = store()
    lease = live.acquire("bx-14", "signal-processing", "capture regression", NOW)
    assert lease.id == "lease-00001"
    assert lease.board == "bx-14"
    assert lease.expires_at == NOW + timedelta(hours=1)
    assert live.for_board("bx-14", NOW) is lease


def test_ids_run_in_order():
    live = store()
    live.acquire("bx-14", "team", "one", NOW)
    second = live.acquire("bx-15", "team", "two", NOW)
    assert second.id == "lease-00002"
    assert len(live) == 2


def test_a_board_cannot_be_leased_twice():
    live = store()
    live.acquire("bx-14", "signal-processing", "capture", NOW)
    with pytest.raises(Conflict) as caught:
        live.acquire("bx-14", "firmware", "bring up", NOW + timedelta(minutes=5))
    assert caught.value.details["holder"] == "signal-processing"
    assert caught.value.code == "conflict"


def test_a_board_frees_up_when_its_lease_is_released():
    live = store()
    first = live.acquire("bx-14", "team-a", "capture", NOW)
    live.release(first.id, NOW + timedelta(minutes=10), "finished")
    second = live.acquire("bx-14", "team-b", "bring up", NOW + timedelta(minutes=11))
    assert second.id != first.id
    assert live.for_board("bx-14", NOW + timedelta(minutes=11)) is second


def test_a_board_frees_up_when_its_lease_runs_out():
    live = store()
    live.acquire("bx-14", "team-a", "capture", NOW, minutes=30)
    later = NOW + timedelta(minutes=31)
    assert live.for_board("bx-14", later) is None
    assert live.acquire("bx-14", "team-b", "bring up", later).holder == "team-b"


def test_an_expired_lease_is_written_down_as_expired_by_the_sweep():
    live = store()
    live.acquire("bx-14", "team-a", "capture", NOW, minutes=30)
    closed = live.sweep(NOW + timedelta(hours=2))
    assert [lease.release_reason for lease in closed] == ["expired"]
    assert closed[0].released_at == NOW + timedelta(minutes=30)
    assert live.sweep(NOW + timedelta(hours=3)) == ()


def test_the_sweep_leaves_live_leases_alone():
    live = store()
    live.acquire("bx-14", "team-a", "capture", NOW, minutes=120)
    assert live.sweep(NOW + timedelta(minutes=30)) == ()


def test_renewing_extends_a_lease():
    live = store()
    lease = live.acquire("bx-14", "team-a", "capture", NOW, minutes=30)
    renewed = live.renew(lease.id, NOW + timedelta(minutes=25), 60)
    assert renewed.expires_at == NOW + timedelta(minutes=85)
    assert live.for_board("bx-14", NOW + timedelta(minutes=60)) is not None


def test_renewal_cannot_go_past_the_store_ceiling():
    live = LeaseStore(ceiling_minutes=60)
    lease = live.acquire("bx-14", "team-a", "capture", NOW, minutes=30)
    renewed = live.renew(lease.id, NOW + timedelta(minutes=20), 600)
    assert renewed.expires_at == NOW + timedelta(minutes=60)


def test_a_lease_longer_than_the_ceiling_is_refused_outright():
    live = LeaseStore(ceiling_minutes=60)
    with pytest.raises(ValidationError) as caught:
        live.acquire("bx-14", "team-a", "capture", NOW, minutes=120)
    assert caught.value.details["ceiling"] == 60


def test_a_lease_of_no_minutes_is_refused():
    with pytest.raises(ValidationError):
        store().acquire("bx-14", "team-a", "capture", NOW, minutes=0)


def test_a_store_with_no_ceiling_is_refused():
    with pytest.raises(ValidationError):
        LeaseStore(ceiling_minutes=0)


def test_a_lease_nobody_issued_is_a_not_found():
    with pytest.raises(NotFound):
        store().lease("lease-09999")


def test_what_a_holder_is_sitting_on():
    live = store()
    live.acquire("bx-14", "signal-processing", "capture", NOW)
    live.acquire("bx-15", "signal-processing", "capture", NOW)
    live.acquire("bx-16", "firmware", "bring up", NOW)
    held = [lease.board for lease in live.held_by("Signal Processing", NOW)]
    assert held == ["bx-14", "bx-15"]
    assert live.counts_by_holder(NOW) == {"signal-processing": 2, "firmware": 1}


def test_holdings_drop_off_when_leases_end():
    live = store()
    lease = live.acquire("bx-14", "team-a", "capture", NOW, minutes=15)
    assert live.counts_by_holder(NOW + timedelta(minutes=20)) == {}
    assert live.held_by("team-a", NOW + timedelta(minutes=20)) == ()
    assert live.lease(lease.id).released is False


def test_active_leases_and_boards_held():
    live = store()
    live.acquire("bx-15", "team-a", "capture", NOW)
    live.acquire("bx-14", "team-b", "capture", NOW + timedelta(minutes=1))
    assert live.boards_held(NOW + timedelta(minutes=2)) == ("bx-14", "bx-15")
    assert [lease.board for lease in live.active(NOW + timedelta(minutes=2))] == ["bx-15", "bx-14"]


def test_the_history_of_a_board_keeps_the_closed_leases():
    live = store()
    first = live.acquire("bx-14", "team-a", "capture", NOW, minutes=10)
    live.release(first.id, NOW + timedelta(minutes=5))
    live.acquire("bx-14", "team-b", "bring up", NOW + timedelta(minutes=6))
    history = live.history("bx-14")
    assert [lease.holder for lease in history] == ["team-a", "team-b"]
    assert len(live.all()) == 2


def test_releasing_a_lease_twice_is_refused():
    live = store()
    lease = live.acquire("bx-14", "team-a", "capture", NOW)
    live.release(lease.id, NOW + timedelta(minutes=5))
    with pytest.raises(ValidationError):
        live.release(lease.id, NOW + timedelta(minutes=6))
