from datetime import UTC, datetime, timedelta

import pytest

from fpga.errors import ValidationError
from fpga.jobs.journal import JobJournal, JournalEntry

NOW = datetime(2025, 10, 16, 9, 0, tzinfo=UTC)


def journal(tmp_path):
    return JobJournal(tmp_path / "journal" / "jobs.jsonl")


def test_a_line_round_trips():
    entry = JournalEntry(at=NOW, job="job-00001", event="started", detail={"board": "bx-14"})
    assert JournalEntry.from_line(entry.to_line()) == entry


def test_a_line_without_detail_leaves_the_key_out():
    entry = JournalEntry(at=NOW, job="job-00001", event="created")
    assert "detail" not in entry.to_line()
    assert JournalEntry.from_line(entry.to_line()).detail == {}


def test_an_event_nobody_has_is_refused():
    with pytest.raises(ValidationError):
        JournalEntry(at=NOW, job="job-00001", event="exploded")


def test_journal_times_are_in_utc():
    with pytest.raises(ValidationError):
        JournalEntry(at=datetime(2025, 10, 16, 9), job="job-00001", event="created")


@pytest.mark.parametrize("line", ["not json", "[]", '{"job": "job-1"}'])
def test_a_line_that_is_not_one_is_refused(line):
    with pytest.raises(ValidationError):
        JournalEntry.from_line(line)


def test_writing_and_reading_a_journal(tmp_path):
    book = journal(tmp_path)
    book.append("job-00001", "created", NOW, board="bx-14")
    book.append("job-00001", "started", NOW + timedelta(seconds=1))
    assert [entry.event for entry in book.entries()] == ["created", "started"]


def test_a_journal_that_does_not_exist_yet_is_empty(tmp_path):
    assert journal(tmp_path).entries() == ()
    assert journal(tmp_path).unfinished() == ()


def test_the_file_is_written_as_it_goes(tmp_path):
    book = journal(tmp_path)
    book.append("job-00001", "created", NOW)
    lines = book.path.read_text(encoding="utf-8").splitlines()
    assert len(lines) == 1
    book.append("job-00001", "started", NOW)
    assert len(book.path.read_text(encoding="utf-8").splitlines()) == 2


def test_entries_come_back_per_job(tmp_path):
    book = journal(tmp_path)
    book.append("job-00001", "created", NOW)
    book.append("job-00002", "created", NOW)
    book.append("job-00001", "started", NOW)
    assert len(book.for_job("job-00001")) == 2
    assert book.last_event("job-00001").event == "started"
    assert book.last_event("job-00009") is None


def test_a_job_that_started_and_never_finished_is_unfinished(tmp_path):
    book = journal(tmp_path)
    book.append("job-00001", "created", NOW)
    book.append("job-00001", "started", NOW)
    assert book.unfinished() == ("job-00001",)


def test_a_job_that_finished_is_not(tmp_path):
    book = journal(tmp_path)
    book.append("job-00001", "started", NOW)
    book.append("job-00001", "passed", NOW + timedelta(minutes=1))
    assert book.unfinished() == ()


def test_a_job_that_failed_is_not_unfinished_either(tmp_path):
    book = journal(tmp_path)
    book.append("job-00001", "started", NOW)
    book.append("job-00001", "failed", NOW, reason="cable")
    assert book.unfinished() == ()


def test_a_job_that_was_retried_and_left_open_shows_up(tmp_path):
    book = journal(tmp_path)
    book.append("job-00001", "started", NOW)
    book.append("job-00001", "failed", NOW + timedelta(seconds=10), reason="cable")
    book.append("job-00001", "started", NOW + timedelta(seconds=20))
    assert book.unfinished() == ("job-00001",)


def test_a_step_leaves_a_job_open(tmp_path):
    book = journal(tmp_path)
    book.append("job-00001", "step", NOW, step="erase")
    assert book.unfinished() == ("job-00001",)


def test_several_open_jobs_come_back_sorted(tmp_path):
    book = journal(tmp_path)
    book.append("job-00002", "started", NOW)
    book.append("job-00001", "started", NOW)
    assert book.unfinished() == ("job-00001", "job-00002")


def test_the_boards_a_journal_mentions(tmp_path):
    book = journal(tmp_path)
    book.append("job-00001", "started", NOW, board="bx-14")
    book.append("job-00002", "started", NOW, board="bx-15")
    book.append("job-00002", "step", NOW, step="erase")
    assert book.boards_touched() == ("bx-14", "bx-15")


def test_an_entry_describes_itself(tmp_path):
    entry = JournalEntry(at=NOW, job="job-00001", event="step", detail={"step": "erase"})
    assert entry.describe() == "2025-10-16T09:00:00+00:00 job-00001 step step=erase"


def test_rotation_keeps_the_tail(tmp_path):
    book = journal(tmp_path)
    for index in range(20):
        book.append("job-00001", "step", NOW, index=index)
    dropped = book.rotate(keep_lines=5)
    assert dropped == 15
    assert len(book.entries()) == 5
    assert book.entries()[-1].detail["index"] == 19


def test_rotation_leaves_a_short_journal_alone(tmp_path):
    book = journal(tmp_path)
    book.append("job-00001", "step", NOW)
    assert book.rotate(keep_lines=100) == 0
    assert len(book.entries()) == 1


def test_rotating_a_journal_that_does_not_exist_does_nothing(tmp_path):
    assert journal(tmp_path).rotate() == 0


def test_keeping_nothing_is_refused(tmp_path):
    with pytest.raises(ValidationError):
        journal(tmp_path).rotate(keep_lines=0)
