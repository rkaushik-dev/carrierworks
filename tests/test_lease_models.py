from datetime import UTC, datetime, timedelta

import pytest

from fpga.errors import ValidationError
from fpga.leases.models import Lease

NOW = datetime(2025, 8, 1, 9, 0, tzinfo=UTC)


def lease(**changes):
    fields = {
        "id": "lease-00001",
        "board": "BX-0014",
        "holder": "Signal Processing",
        "purpose": "nightly capture regression",
        "taken_at": NOW,
        "expires_at": NOW + timedelta(hours=1),
    }
    fields.update(changes)
    return Lease(**fields)


def test_a_lease_normalises_its_names():
    live = lease()
    assert live.board == "bx-14"
    assert live.holder == "signal-processing"
    assert live.priority == 5


def test_a_lease_is_active_until_it_expires():
    live = lease()
    assert live.active(NOW)
    assert live.active(NOW + timedelta(minutes=59))
    assert not live.active(NOW + timedelta(hours=1))
    assert live.expired(NOW + timedelta(hours=1))


def test_what_is_left_runs_down_to_nothing():
    live = lease()
    assert live.remaining(NOW) == timedelta(hours=1)
    assert live.remaining(NOW + timedelta(minutes=45)) == timedelta(minutes=15)
    assert live.remaining(NOW + timedelta(hours=2)) == timedelta(0)


def test_how_long_a_board_was_held_stops_at_the_expiry():
    live = lease()
    assert live.held_for(NOW + timedelta(minutes=20)) == timedelta(minutes=20)
    assert live.held_for(NOW + timedelta(hours=5)) == timedelta(hours=1)


def test_releasing_stops_the_clock():
    released = lease().release(NOW + timedelta(minutes=20), "job finished")
    assert released.released
    assert not released.active(NOW + timedelta(minutes=21))
    assert released.held_for(NOW + timedelta(hours=3)) == timedelta(minutes=20)
    assert "released (job finished)" in released.describe()


def test_a_lease_cannot_be_released_twice():
    released = lease().release(NOW + timedelta(minutes=5))
    with pytest.raises(ValidationError):
        released.release(NOW + timedelta(minutes=6))


def test_renewing_pushes_the_expiry_out_from_now():
    live = lease()
    renewed = live.renewed(NOW + timedelta(minutes=50), 60)
    assert renewed.expires_at == NOW + timedelta(minutes=110)
    assert renewed.renewals == 1


def test_renewal_stops_at_the_ceiling_measured_from_the_start():
    live = lease()
    renewed = live.renewed(NOW + timedelta(minutes=50), 600, ceiling_minutes=120)
    assert renewed.expires_at == NOW + timedelta(minutes=120)


def test_a_lease_past_its_ceiling_cannot_be_renewed_at_all():
    live = lease()
    with pytest.raises(ValidationError) as caught:
        live.renewed(NOW + timedelta(minutes=200), 30, ceiling_minutes=120)
    assert caught.value.details["ceiling_minutes"] == 120


def test_a_released_lease_cannot_be_renewed():
    released = lease().release(NOW + timedelta(minutes=5))
    with pytest.raises(ValidationError):
        released.renewed(NOW + timedelta(minutes=6), 30)


def test_renewing_for_no_time_is_a_bug():
    with pytest.raises(ValidationError):
        lease().renewed(NOW, 0)


def test_expiring_a_lease_closes_it_at_its_own_expiry():
    expired = lease().expire(NOW + timedelta(hours=3))
    assert expired.released_at == NOW + timedelta(hours=1)
    assert expired.release_reason == "expired"


def test_expiring_a_released_lease_changes_nothing():
    released = lease().release(NOW + timedelta(minutes=5))
    assert released.expire(NOW + timedelta(hours=2)) == released


def test_priority_decides_who_outranks_whom():
    urgent = lease(priority=2)
    ordinary = lease(priority=5)
    assert urgent.outranks(ordinary)
    assert not ordinary.outranks(urgent)
    assert not ordinary.outranks(lease(priority=5))


def test_preemptibility_is_not_priority():
    stubborn = lease(priority=9, preemptible=False)
    assert not stubborn.preemptible
    assert lease(priority=1).preemptible


@pytest.mark.parametrize("priority", [0, 10, -1])
def test_a_priority_off_the_scale_is_refused(priority):
    with pytest.raises(ValidationError):
        lease(priority=priority)


def test_a_lease_that_expires_before_it_starts_is_refused():
    with pytest.raises(ValidationError):
        lease(expires_at=NOW - timedelta(minutes=1))


def test_a_lease_without_a_purpose_is_refused():
    with pytest.raises(ValidationError):
        lease(purpose="   ")


def test_lease_times_are_in_utc():
    with pytest.raises(ValidationError):
        lease(taken_at=datetime(2025, 8, 1, 9, 0))


def test_a_live_lease_describes_when_it_runs_out():
    assert lease().describe().endswith("until 2025-08-01T10:00+00:00")
