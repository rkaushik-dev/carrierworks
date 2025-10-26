from datetime import UTC, datetime, timedelta

import pytest

from fpga.errors import NotFound, ValidationError
from fpga.jobs.store import JobStore

NOW = datetime(2025, 10, 28, 8, 0, tzinfo=UTC)
DIGEST = "ef" * 32


def store():
    return JobStore()


def submit(live, board="bx-14", when=NOW, mode="jtag"):
    return live.submit(board, DIGEST, mode, "team-a", when)


def test_a_job_goes_on_the_store():
    live = store()
    job = submit(live)
    assert job.id == "job-00001"
    assert live.job("job-00001") == job
    assert live.queued() == (job,)
    assert len(live) == 1


def test_a_job_nobody_submitted_is_a_not_found():
    with pytest.raises(NotFound):
        store().job("job-09999")
    with pytest.raises(NotFound):
        store().hold_until("job-09999", NOW)


def test_the_oldest_queued_job_is_offered_first():
    live = store()
    first = submit(live, when=NOW)
    second = submit(live, board="bx-15", when=NOW + timedelta(minutes=1))
    assert [job.id for job in live.ready(NOW + timedelta(minutes=2))] == [first.id, second.id]
    assert live.next_ready(NOW + timedelta(minutes=2)).id == first.id


def test_two_jobs_for_one_board_do_not_both_get_offered():
    live = store()
    first = submit(live, when=NOW)
    submit(live, when=NOW + timedelta(minutes=1))
    ready = live.ready(NOW + timedelta(minutes=2))
    assert [job.id for job in ready] == [first.id]


def test_a_running_job_keeps_its_board_busy():
    live = store()
    first = submit(live)
    submit(live, when=NOW + timedelta(minutes=1))
    live.update(first.start(NOW))
    assert live.busy_boards() == ("bx-14",)
    assert live.ready(NOW + timedelta(minutes=2)) == ()


def test_a_finished_job_frees_the_board():
    live = store()
    first = submit(live)
    second = submit(live, when=NOW + timedelta(minutes=1))
    live.update(first.start(NOW).passed(NOW + timedelta(minutes=2)))
    assert [job.id for job in live.ready(NOW + timedelta(minutes=3))] == [second.id]


def test_a_backoff_keeps_a_job_off_the_ready_list():
    live = store()
    job = submit(live)
    live.hold_until(job.id, NOW + timedelta(seconds=30))
    assert live.ready(NOW) == ()
    assert [held.id for held in live.waiting_for_backoff(NOW)] == [job.id]
    assert live.ready(NOW + timedelta(seconds=31))[0].id == job.id
    assert live.held_until(job.id) == NOW + timedelta(seconds=30)


def test_a_finished_job_forgets_its_backoff():
    live = store()
    job = submit(live)
    live.hold_until(job.id, NOW + timedelta(seconds=30))
    live.update(job.start(NOW).passed(NOW))
    assert live.held_until(job.id) is None


def test_updating_a_job_that_is_not_there_is_a_not_found():
    live = store()
    job = submit(live)
    other = JobStore().submit("bx-15", DIGEST, "jtag", "team-a", NOW)
    live.update(job.start(NOW))
    with pytest.raises(NotFound):
        JobStore().update(other.start(NOW))


def test_jobs_come_back_by_board_and_by_state():
    live = store()
    submit(live, board="bx-14")
    second = submit(live, board="bx-15")
    live.update(second.start(NOW))
    assert [job.board for job in live.for_board("BX-0014")] == ["bx-14"]
    assert [job.id for job in live.running()] == [second.id]
    assert live.last_for_board("bx-15").id == second.id
    assert live.last_for_board("bx-99") is None


def test_a_cancelled_job_leaves_the_queue():
    live = store()
    job = submit(live)
    live.cancel(job.id, NOW + timedelta(minutes=1), "board went to maintenance")
    assert live.queued() == ()
    assert live.job(job.id).error == "board went to maintenance"


def test_a_job_that_never_finished_is_abandoned_after_the_limit():
    live = store()
    job = submit(live)
    live.update(job.start(NOW))
    closed = live.abandon_stale(NOW + timedelta(hours=2), timedelta(hours=1))
    assert [job.id for job in closed] == [job.id]
    assert live.job(job.id).state == "failed"
    assert live.job(job.id).error_code == "abandoned"


def test_a_job_inside_the_limit_is_left_running():
    live = store()
    job = submit(live)
    live.update(job.start(NOW))
    assert live.abandon_stale(NOW + timedelta(minutes=10), timedelta(hours=1)) == ()
    assert live.job(job.id).state == "running"


def test_a_limit_of_nothing_is_a_bug():
    with pytest.raises(ValidationError):
        store().abandon_stale(NOW, timedelta(0))


def test_ids_run_in_order_and_are_stable():
    live = store()
    assert submit(live).id == "job-00001"
    assert submit(live, board="bx-15").id == "job-00002"
    assert [job.id for job in live.all()] == ["job-00001", "job-00002"]
