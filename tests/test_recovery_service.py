from datetime import UTC, datetime, timedelta

import pytest

from boardsetup import artix, kintex
from fpga.artifacts.registry import ArtifactStore
from fpga.bitstream.image import read_bitstream
from fpga.config import BenchSettings
from fpga.jobs.journal import JobJournal
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory, leasing, programming, recovery, registry
from images import bitfile

WHEN = datetime(2026, 4, 24, 3, 0, tzinfo=UTC)
CRASH = WHEN - timedelta(minutes=4)


@pytest.fixture()
def journal(tmp_path):
    return JobJournal(tmp_path / "jobs.jsonl")


@pytest.fixture()
def unit(tmp_path):
    database = BenchDatabase.in_memory()
    with database.unit() as open_unit:
        inventory.add_board(open_unit, kintex("bx-14"), WHEN - timedelta(days=1))
        inventory.add_board(open_unit, artix("bx-15"), WHEN - timedelta(days=1))
        yield open_unit


@pytest.fixture()
def digest(unit, tmp_path):
    store = ArtifactStore(tmp_path / "artifacts")
    return registry.ingest(unit, store, read_bitstream(bitfile()), WHEN, channel="qual").digest


def in_flight(unit, journal, digest, *, board="bx-14", mode="jtag", started=True, attempts=1):
    """A job the database thinks is running, as a crash would have left it."""
    leasing.acquire(unit, board, "team-a", "capture", CRASH - timedelta(minutes=1))
    job = programming.submit(unit, board, digest, mode, "team-a", CRASH)
    running = job
    for _ in range(attempts):
        running = running.start(CRASH)
        if _ + 1 < attempts:
            running = running.failed(CRASH, "transient", code="chain", retryable=True)
    unit.jobs.save(running)
    inventory.move_state(unit, board, "programming", CRASH, actor="team-a")
    if started:
        journal.append(job.id, "started", CRASH, board=board, mode=mode)
    return running


def test_a_bench_with_nothing_running_has_nothing_to_recover(unit, journal):
    assert recovery.interrupted(unit, journal) == ()
    assert recovery.recover(unit, journal, WHEN).clean is True


def test_a_running_job_with_an_open_journal_line_is_interrupted(unit, journal, digest):
    job = in_flight(unit, journal, digest)
    assert [entry.id for entry in recovery.interrupted(unit, journal)] == [job.id]


def test_a_running_job_the_journal_never_saw_is_interrupted_too(unit, journal, digest):
    job = in_flight(unit, journal, digest, started=False)
    assert [entry.id for entry in recovery.interrupted(unit, journal)] == [job.id]


def test_a_job_the_journal_closed_is_not_interrupted(unit, journal, digest):
    job = in_flight(unit, journal, digest)
    journal.append(job.id, "passed", CRASH, board="bx-14")
    assert recovery.interrupted(unit, journal) == ()


def test_an_interrupted_jtag_load_goes_back_on_the_queue(unit, journal, digest):
    job = in_flight(unit, journal, digest)
    report = recovery.recover(unit, journal, WHEN)
    assert report.jobs_requeued == (job.id,)
    assert unit.jobs.get(job.id).state == "queued"


def test_a_requeued_job_leaves_the_board_usable(unit, journal, digest):
    in_flight(unit, journal, digest)
    recovery.recover(unit, journal, WHEN)
    assert unit.boards.state("bx-14").state == "leased"


def test_a_requeued_job_on_a_board_nobody_holds_goes_back_to_available(unit, journal, digest):
    job = in_flight(unit, journal, digest)
    held = unit.leases.for_board("bx-14", WHEN)
    assert held is not None
    leasing.release(unit, held.id, CRASH, "gave it back")
    recovery.recover(unit, journal, WHEN)
    assert unit.boards.state("bx-14").state == "available"
    assert unit.jobs.get(job.id).state == "queued"


def test_an_interrupted_flash_write_faults_the_board(unit, journal, digest):
    job = in_flight(unit, journal, digest, mode="flash-update")
    report = recovery.recover(unit, journal, WHEN)
    assert report.boards_faulted == ("bx-14",)
    assert unit.boards.state("bx-14").state == "faulted"
    assert unit.jobs.get(job.id).state == "failed"


def test_a_faulted_board_gives_its_lease_back(unit, journal, digest):
    in_flight(unit, journal, digest, mode="flash-golden")
    recovery.recover(unit, journal, WHEN)
    assert unit.leases.for_board("bx-14", WHEN) is None


def test_a_job_out_of_attempts_is_not_requeued(unit, journal, digest):
    job = in_flight(unit, journal, digest, attempts=3)
    report = recovery.recover(unit, journal, WHEN)
    assert report.jobs_closed == (job.id,)
    assert unit.jobs.get(job.id).state == "failed"
    assert unit.boards.state("bx-14").state == "leased"


def test_the_attempt_limit_comes_from_the_settings(unit, journal, digest):
    job = in_flight(unit, journal, digest, attempts=2)
    report = recovery.recover(unit, journal, WHEN, settings=BenchSettings(program_attempts=2))
    assert report.jobs_closed == (job.id,)


def test_recovery_closes_the_journal_line(unit, journal, digest):
    job = in_flight(unit, journal, digest)
    recovery.recover(unit, journal, WHEN)
    last = journal.last_event(job.id)
    assert last is not None
    assert last.event == "abandoned"
    assert recovery.interrupted(unit, journal) == ()


def test_recovery_leaves_a_line_in_the_audit_trail(unit, journal, digest):
    job = in_flight(unit, journal, digest)
    recovery.recover(unit, journal, WHEN)
    trail = unit.audit.for_subject(job.id)
    assert [entry.action for entry in trail if entry.actor == "recovery"] == ["requeued"]
    board_lines = unit.audit.for_subject("bx-14")
    assert "returned" in [entry.action for entry in board_lines if entry.actor == "recovery"]


def test_two_interrupted_jobs_are_both_dealt_with(unit, journal, digest):
    first = in_flight(unit, journal, digest, board="bx-14", mode="jtag")
    in_flight(unit, journal, digest, board="bx-15", mode="flash-update")
    report = recovery.recover(unit, journal, WHEN)
    assert report.jobs_requeued == (first.id,)
    assert report.boards_faulted == ("bx-15",)
    assert report.boards_returned == ("bx-14",)


def test_running_recovery_twice_changes_nothing_the_second_time(unit, journal, digest):
    in_flight(unit, journal, digest)
    recovery.recover(unit, journal, WHEN)
    assert recovery.recover(unit, journal, WHEN + timedelta(minutes=1)).clean is True


def test_the_report_reads_as_one_line(unit, journal, digest):
    in_flight(unit, journal, digest)
    assert "requeued job-00001" in recovery.recover(unit, journal, WHEN).describe()
