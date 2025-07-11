from datetime import UTC, datetime, timedelta

import pytest

from fpga.errors import ValidationError
from fpga.inventory.faults import FaultLog, FaultRecord, QuarantinePolicy

NOW = datetime(2025, 7, 14, 18, 0, tzinfo=UTC)


def log_with(*faults):
    log = FaultLog()
    for kind, ago in faults:
        log.add("bx-14", kind, NOW - ago)
    return log


def test_a_fault_records_what_and_when():
    fault = FaultRecord(board="BX-0014", kind="chain", at=NOW, detail="all ones", job="job-1")
    assert fault.board == "bx-14"
    assert fault.describe() == "bx-14 chain at 2025-07-14T18:00:00+00:00: all ones [job-1]"


def test_a_fault_kind_nobody_has_is_refused():
    with pytest.raises(ValidationError):
        FaultRecord(board="bx-14", kind="gremlins", at=NOW)


def test_fault_times_are_in_utc():
    with pytest.raises(ValidationError):
        FaultRecord(board="bx-14", kind="chain", at=datetime(2025, 7, 14, 18, 0))


def test_faults_are_kept_per_board():
    log = FaultLog()
    log.add("bx-14", "chain", NOW)
    log.add("bx-15", "power", NOW)
    assert len(log.for_board("bx-14")) == 1
    assert log.boards() == ("bx-14", "bx-15")
    assert len(log.all()) == 2


def test_the_last_fault_on_a_board_comes_back():
    log = log_with(("chain", timedelta(days=2)), ("verify", timedelta(hours=1)))
    last = log.last("bx-14")
    assert last is not None
    assert last.kind == "verify"
    assert log.last("bx-15") is None


def test_a_window_only_counts_what_is_inside_it():
    log = log_with(
        ("chain", timedelta(days=9)),
        ("chain", timedelta(days=3)),
        ("chain", timedelta(hours=2)),
    )
    assert log.count("bx-14", NOW, timedelta(days=7)) == 2
    assert log.count("bx-14", NOW, timedelta(days=1)) == 1
    assert log.count("bx-14", NOW, timedelta(days=30)) == 3


def test_a_window_can_be_asked_about_one_kind():
    log = log_with(("chain", timedelta(hours=1)), ("verify", timedelta(hours=2)))
    assert log.count("bx-14", NOW, timedelta(days=1), kind="verify") == 1
    assert log.count("bx-14", NOW, timedelta(days=1), kind="power") == 0


def test_a_window_of_no_length_is_a_bug():
    with pytest.raises(ValidationError):
        FaultLog().count("bx-14", NOW, timedelta(0))


def test_a_quiet_board_is_not_quarantined():
    policy = QuarantinePolicy()
    log = log_with(("chain", timedelta(days=2)))
    assert policy.verdict(log, "bx-14", NOW) is None
    assert not policy.should_quarantine(log, "bx-14", NOW)


def test_three_faults_in_a_week_is_enough():
    policy = QuarantinePolicy()
    log = log_with(
        ("chain", timedelta(days=6)),
        ("power", timedelta(days=3)),
        ("thermal", timedelta(days=1)),
    )
    assert policy.verdict(log, "bx-14", NOW) == "3 faults inside 7 days"


def test_the_same_three_faults_spread_out_are_not():
    policy = QuarantinePolicy()
    log = log_with(
        ("chain", timedelta(days=20)),
        ("power", timedelta(days=12)),
        ("thermal", timedelta(days=9)),
    )
    assert policy.verdict(log, "bx-14", NOW) is None


def test_two_faults_in_an_afternoon_is_enough_on_its_own():
    policy = QuarantinePolicy()
    log = log_with(("chain", timedelta(hours=6)), ("power", timedelta(hours=1)))
    assert policy.verdict(log, "bx-14", NOW) == "2 faults inside 8 hours"


def test_two_verify_failures_are_reported_as_verify_failures():
    policy = QuarantinePolicy()
    log = log_with(("verify", timedelta(days=5)), ("verify", timedelta(days=1)))
    assert policy.verdict(log, "bx-14", NOW) == "2 verify failures inside 7 days"


def test_the_sharpest_reason_wins_when_several_apply():
    policy = QuarantinePolicy()
    log = log_with(
        ("verify", timedelta(hours=3)),
        ("verify", timedelta(hours=1)),
        ("chain", timedelta(days=2)),
    )
    assert policy.verdict(log, "bx-14", NOW) == "2 verify failures inside 7 days"


def test_the_thresholds_are_settable_for_a_bench_with_older_boards():
    policy = QuarantinePolicy(total_faults=6, short_window_faults=4, verify_faults=3)
    log = log_with(
        ("chain", timedelta(days=6)),
        ("power", timedelta(days=3)),
        ("thermal", timedelta(days=1)),
    )
    assert policy.verdict(log, "bx-14", NOW) is None


def test_a_policy_with_a_short_window_longer_than_its_long_one_is_refused():
    with pytest.raises(ValidationError):
        QuarantinePolicy(window=timedelta(hours=1), short_window=timedelta(days=1))


def test_a_policy_with_a_threshold_of_nothing_is_refused():
    with pytest.raises(ValidationError):
        QuarantinePolicy(total_faults=0)


def test_maintenance_clears_a_board_and_says_how_many_it_dropped():
    log = log_with(("chain", timedelta(hours=2)), ("verify", timedelta(hours=1)))
    log.add("bx-15", "chain", NOW)
    assert log.clear("bx-14") == 2
    assert log.for_board("bx-14") == ()
    assert len(log.for_board("bx-15")) == 1


def test_a_cleared_board_stops_being_quarantine_worthy():
    policy = QuarantinePolicy()
    log = log_with(("verify", timedelta(hours=3)), ("verify", timedelta(hours=1)))
    assert policy.should_quarantine(log, "bx-14", NOW)
    log.clear("bx-14")
    assert not policy.should_quarantine(log, "bx-14", NOW)


def test_a_one_day_window_reads_as_a_day():
    policy = QuarantinePolicy(window=timedelta(days=1), short_window=timedelta(hours=1))
    log = log_with(("verify", timedelta(hours=5)), ("verify", timedelta(hours=2)))
    assert policy.verdict(log, "bx-14", NOW) == "2 verify failures inside a day"
