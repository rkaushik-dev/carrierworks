from datetime import UTC, datetime, timedelta

import pytest

from boardsetup import artix, kintex
from fpga.benchcalendar import BenchCalendar
from fpga.capabilities import RequirementSet
from fpga.errors import ValidationError
from fpga.inventory.book import BoardBook
from fpga.leases.store import LeaseStore
from fpga.scheduling.queue import RequestQueue
from fpga.scheduling.service import Scheduler

# A wednesday, 14:00 utc, which is 09:00 in the lab.
NOW = datetime(2025, 8, 20, 14, 0, tzinfo=UTC)


def wanted(*requirements):
    return RequirementSet.parse(list(requirements))


def bench(**rest):
    book = BoardBook(when=NOW - timedelta(days=1))
    book.add(kintex("bx-14", serial=None, outlet=None))
    book.add(artix("bx-15", serial=None, outlet=None))
    return Scheduler(book, LeaseStore(), RequestQueue(), **rest)


def test_a_request_gets_a_board():
    live = bench()
    request = live.queue.submit("team-a", "capture", wanted("adc-14bit"), NOW)
    result = live.dispatch(NOW)
    assert result.served == 1
    assignment = result.assignments[0]
    assert assignment.request == request.id
    assert assignment.board == "bx-14"
    assert live.book.state("bx-14").state == "leased"
    assert not live.queue.request(request.id).open


def test_the_smallest_board_that_fits_is_the_one_handed_out():
    live = bench()
    live.queue.submit("team-a", "anything", wanted("jtag"), NOW)
    result = live.dispatch(NOW)
    assert result.assignments[0].board == "bx-15"


def test_a_request_nothing_matches_stays_on_the_queue_with_a_reason():
    live = bench()
    request = live.queue.submit("team-a", "capture", wanted("ddr4-sodimm"), NOW)
    result = live.dispatch(NOW)
    assert result.served == 0
    assert result.reason_for(request.id) == "nothing free matches what it asked for"
    assert live.queue.request(request.id).open


def test_two_requests_take_two_boards():
    live = bench()
    live.queue.submit("team-a", "one", wanted("jtag"), NOW)
    live.queue.submit("team-b", "two", wanted("jtag"), NOW + timedelta(minutes=1))
    result = live.dispatch(NOW + timedelta(minutes=2))
    assert {assignment.board for assignment in result.assignments} == {"bx-14", "bx-15"}


def test_a_third_request_waits_when_the_bench_is_full():
    live = bench()
    live.queue.submit("team-a", "one", wanted("jtag"), NOW)
    live.queue.submit("team-b", "two", wanted("jtag"), NOW)
    third = live.queue.submit("team-c", "three", wanted("jtag"), NOW)
    result = live.dispatch(NOW)
    assert result.served == 2
    assert result.reason_for(third.id) is not None


def test_a_quota_holds_a_team_to_its_share():
    live = bench(quotas={"team-a": 1})
    live.queue.submit("team-a", "one", wanted("jtag"), NOW)
    live.dispatch(NOW)
    second = live.queue.submit("team-a", "two", wanted("jtag"), NOW + timedelta(minutes=1))
    result = live.dispatch(NOW + timedelta(minutes=2))
    assert result.served == 0
    reason = result.reason_for(second.id)
    assert reason is not None
    assert "quota is 1" in reason


def test_a_job_needing_hands_waits_until_the_lab_is_staffed():
    live = bench(calendar=BenchCalendar.weekdays())
    request = live.queue.submit("team-a", "reseat", wanted("jtag"), NOW, needs_hands=True)
    night = NOW + timedelta(hours=12)
    result = live.dispatch(night)
    reason = result.reason_for(request.id)
    assert reason is not None
    assert "needs somebody in the lab" in reason
    assert live.dispatch(NOW).served == 1


def test_an_urgent_job_takes_a_board_off_a_low_priority_holder():
    live = bench()
    live.queue.submit("team-a", "experiment", wanted("adc-14bit"), NOW, priority=8)
    live.dispatch(NOW)
    later = NOW + timedelta(minutes=30)
    live.queue.submit("release", "verification", wanted("adc-14bit"), later, priority=2)
    result = live.dispatch(later)
    assert result.served == 1
    assignment = result.assignments[0]
    assert assignment.board == "bx-14"
    assert assignment.preempted is not None
    assert assignment.preempted.holder == "team-a"
    assert "taken from team-a" in assignment.describe()


def test_a_preempted_lease_is_closed_with_its_reason():
    live = bench()
    live.queue.submit("team-a", "experiment", wanted("adc-14bit"), NOW, priority=8)
    live.dispatch(NOW)
    later = NOW + timedelta(minutes=30)
    live.queue.submit("release", "verification", wanted("adc-14bit"), later, priority=2)
    live.dispatch(later)
    closed = live.leases.lease("lease-00001")
    assert closed.released
    assert closed.release_reason == "preempted"


def test_an_equal_priority_job_does_not_get_to_preempt():
    live = bench()
    live.queue.submit("team-a", "experiment", wanted("adc-14bit"), NOW, priority=5)
    live.dispatch(NOW)
    later = NOW + timedelta(minutes=30)
    request = live.queue.submit("team-b", "another", wanted("adc-14bit"), later, priority=5)
    result = live.dispatch(later)
    assert result.served == 0
    assert result.reason_for(request.id) is not None


def test_releasing_a_board_puts_it_back_into_service():
    live = bench()
    live.queue.submit("team-a", "one", wanted("jtag"), NOW)
    lease = live.dispatch(NOW).assignments[0].lease
    live.release(lease.id, NOW + timedelta(minutes=20))
    assert live.book.state(lease.board).usable
    assert live.leases.for_board(lease.board, NOW + timedelta(minutes=21)) is None


def test_a_faulted_board_gives_its_lease_back_and_leaves_service():
    live = bench()
    live.queue.submit("team-a", "one", wanted("jtag"), NOW)
    assignment = live.dispatch(NOW).assignments[0]
    live.fault(assignment.board, NOW + timedelta(minutes=5), "chain read as all ones")
    assert live.book.state(assignment.board).state == "faulted"
    assert live.leases.lease(assignment.lease.id).release_reason == "board faulted"
    assert assignment.board not in [board.tag for board in live.book.available()]


def test_a_fault_needs_a_reason():
    live = bench()
    with pytest.raises(ValidationError):
        live.fault("bx-14", NOW, "  ")


def test_an_expired_lease_is_swept_before_anything_else_happens():
    live = bench()
    live.queue.submit("team-a", "one", wanted("adc-14bit"), NOW, minutes=30)
    live.dispatch(NOW)
    live.book.move_to("bx-14", "available", NOW + timedelta(minutes=31), actor="test")
    live.queue.submit("team-b", "two", wanted("adc-14bit"), NOW + timedelta(minutes=31))
    result = live.dispatch(NOW + timedelta(minutes=31))
    assert result.served == 1
    assert live.leases.lease("lease-00001").release_reason == "expired"


def test_a_tick_with_nothing_waiting_says_so():
    assert bench().dispatch(NOW).describe() == "nothing waiting"


def test_the_result_describes_what_happened():
    live = bench()
    live.queue.submit("team-a", "one", wanted("jtag"), NOW)
    live.queue.submit("team-b", "two", wanted("ddr4-sodimm"), NOW)
    described = live.dispatch(NOW).describe()
    assert "got bx-15" in described
    assert "nothing free matches" in described
