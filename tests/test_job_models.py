from datetime import UTC, datetime, timedelta

import pytest

from fpga.errors import ValidationError
from fpga.jobs.models import ProgramJob

NOW = datetime(2025, 9, 29, 11, 0, tzinfo=UTC)
DIGEST = "ab" * 32


def job(**changes):
    fields = {
        "id": "job-00001",
        "board": "BX-0014",
        "digest": DIGEST,
        "mode": "jtag",
        "requested_by": "Signal Processing",
        "created_at": NOW,
    }
    fields.update(changes)
    return ProgramJob(**fields)


def test_a_job_normalises_its_names():
    live = job()
    assert live.board == "bx-14"
    assert live.requested_by == "signal-processing"
    assert live.state == "queued"
    assert live.short_digest == DIGEST[:12]


def test_the_modes_are_told_apart():
    assert not job().writes_flash
    assert job(mode="flash-update").writes_flash
    assert not job(mode="flash-update").touches_golden
    assert job(mode="flash-golden").touches_golden


def test_a_mode_nobody_has_is_refused():
    with pytest.raises(ValidationError):
        job(mode="serial")


def test_a_short_digest_is_refused_because_the_store_needs_a_whole_one():
    with pytest.raises(ValidationError):
        job(digest="abcdef")


def test_job_times_are_in_utc():
    with pytest.raises(ValidationError):
        job(created_at=datetime(2025, 9, 29, 11, 0))


def test_starting_a_job_counts_the_attempt():
    started = job().start(NOW + timedelta(seconds=5), lease="lease-00001")
    assert started.state == "running"
    assert started.attempts == 1
    assert started.started_at == NOW + timedelta(seconds=5)
    assert started.lease == "lease-00001"


def test_a_job_cannot_be_started_twice():
    started = job().start(NOW)
    with pytest.raises(ValidationError):
        started.start(NOW + timedelta(seconds=1))


def test_a_passing_job_keeps_its_first_start_time():
    started = job().start(NOW)
    done = started.passed(NOW + timedelta(minutes=2), clocks=48_000)
    assert done.succeeded
    assert done.finished
    assert done.clocks == 48_000
    assert done.duration() == timedelta(minutes=2)


def test_a_job_that_has_not_run_has_no_duration():
    assert job().duration() is None
    assert job().start(NOW).duration() is None


def test_a_retryable_failure_puts_the_job_back_on_the_queue():
    started = job().start(NOW)
    again = started.failed(
        NOW + timedelta(seconds=30),
        "chain read as all ones",
        code="chain",
        retryable=True,
    )
    assert again.state == "queued"
    assert again.finished_at is None
    assert again.attempts == 1
    assert again.error_code == "chain"


def test_a_permanent_failure_ends_the_job():
    started = job().start(NOW)
    dead = started.failed(NOW + timedelta(seconds=30), "image is for another device")
    assert dead.state == "failed"
    assert dead.finished
    assert not dead.succeeded
    assert dead.finished_at == NOW + timedelta(seconds=30)


def test_a_second_attempt_counts_up():
    live = job().start(NOW)
    live = live.failed(NOW + timedelta(seconds=10), "cable", retryable=True)
    live = live.start(NOW + timedelta(seconds=20))
    assert live.attempts == 2
    assert live.started_at == NOW


def test_attempts_run_out():
    live = job().start(NOW)
    assert not live.exhausted(3)
    live = live.failed(NOW, "cable", retryable=True).start(NOW)
    live = live.failed(NOW, "cable", retryable=True).start(NOW)
    assert live.exhausted(3)


def test_a_failure_has_to_say_why():
    with pytest.raises(ValidationError):
        job().start(NOW).failed(NOW, "   ")


def test_only_a_running_job_can_pass_or_fail():
    with pytest.raises(ValidationError):
        job().passed(NOW)
    with pytest.raises(ValidationError):
        job().failed(NOW, "nope")


def test_a_queued_job_can_be_cancelled():
    cancelled = job().cancel(NOW + timedelta(minutes=1), "board went away")
    assert cancelled.state == "cancelled"
    assert cancelled.finished
    assert cancelled.error == "board went away"


def test_a_finished_job_cannot_be_cancelled_or_restarted():
    done = job().start(NOW).passed(NOW + timedelta(minutes=1))
    with pytest.raises(ValidationError):
        done.cancel(NOW + timedelta(minutes=2))
    with pytest.raises(ValidationError):
        done.start(NOW + timedelta(minutes=2))


def test_a_job_describes_itself_with_its_error():
    live = job().start(NOW).failed(NOW, "image is for another device")
    assert live.describe() == (
        "job-00001 jtag abababababab on bx-14 [failed] image is for another device"
    )
    assert job().describe() == "job-00001 jtag abababababab on bx-14 [queued]"
