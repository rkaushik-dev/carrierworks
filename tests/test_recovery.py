from datetime import UTC, datetime, timedelta

from boardsetup import artix, kintex
from fpga.inventory.book import BoardBook
from fpga.jobs.journal import JobJournal
from fpga.jobs.recovery import recover
from fpga.jobs.store import JobStore
from fpga.leases.store import LeaseStore

NOW = datetime(2025, 11, 1, 7, 0, tzinfo=UTC)
DIGEST = "ab" * 32


class Bench:
    def __init__(self, tmp_path):
        self.book = BoardBook(when=NOW - timedelta(days=2))
        self.book.add(kintex("bx-14", serial=None, outlet=None))
        self.book.add(artix("bx-15", serial=None, outlet=None))
        self.jobs = JobStore()
        self.journal = JobJournal(tmp_path / "journal.jsonl")
        self.leases = LeaseStore()

    def start_job(self, board="bx-14", mode="jtag", when=None):
        moment = when or NOW - timedelta(minutes=10)
        lease = self.leases.acquire(board, "team-a", "capture", moment, minutes=120)
        self.book.move_to(board, "leased", moment, actor="team-a")
        job = self.jobs.submit(board, DIGEST, mode, "team-a", moment)
        self.jobs.update(job.start(moment, lease=lease.id))
        self.journal.append(job.id, "started", moment, board=board, mode=mode)
        self.book.move_to(board, "programming", moment, actor="team-a")
        return job

    def recover(self, when=NOW):
        return recover(self.journal, self.jobs, self.book, self.leases, when)


def test_a_clean_bench_recovers_to_nothing(tmp_path):
    report = Bench(tmp_path).recover()
    assert report.clean
    assert report.describe() == "nothing was left half done"


def test_an_interrupted_jtag_job_is_closed_and_the_board_comes_back(tmp_path):
    bench = Bench(tmp_path)
    job = bench.start_job()
    report = bench.recover()
    assert report.jobs_closed == (job.id,)
    assert bench.jobs.job(job.id).state == "failed"
    assert bench.jobs.job(job.id).error_code == "abandoned"
    assert bench.book.state("bx-14").state == "leased"


def test_a_board_whose_lease_expired_goes_all_the_way_back(tmp_path):
    bench = Bench(tmp_path)
    bench.start_job()
    report = bench.recover(NOW + timedelta(hours=4))
    assert report.boards_returned == ("bx-14",)
    assert bench.book.state("bx-14").usable


def test_an_interrupted_flash_job_faults_the_board(tmp_path):
    bench = Bench(tmp_path)
    job = bench.start_job(mode="flash-update")
    report = bench.recover()
    assert report.boards_faulted == ("bx-14",)
    assert bench.book.state("bx-14").state == "faulted"
    assert "interrupted" in bench.book.state("bx-14").reason
    assert bench.jobs.job(job.id).state == "failed"


def test_a_faulted_board_gives_its_lease_back(tmp_path):
    bench = Bench(tmp_path)
    bench.start_job(mode="flash-golden")
    bench.recover()
    assert bench.leases.for_board("bx-14", NOW) is None


def test_the_abandonment_is_journalled(tmp_path):
    bench = Bench(tmp_path)
    job = bench.start_job()
    bench.recover()
    assert bench.journal.last_event(job.id).event == "abandoned"
    assert bench.journal.unfinished() == ()


def test_recovery_runs_twice_and_finds_nothing_the_second_time(tmp_path):
    bench = Bench(tmp_path)
    bench.start_job()
    bench.recover()
    assert bench.recover().clean


def test_two_boards_are_both_settled(tmp_path):
    bench = Bench(tmp_path)
    bench.start_job(board="bx-14", mode="jtag")
    bench.start_job(board="bx-15", mode="flash-update")
    report = bench.recover()
    assert len(report.jobs_closed) == 2
    assert report.boards_faulted == ("bx-15",)
    assert bench.book.state("bx-14").state == "leased"


def test_a_board_left_programming_with_no_job_is_settled_too(tmp_path):
    bench = Bench(tmp_path)
    bench.leases.acquire("bx-14", "team-a", "capture", NOW - timedelta(minutes=5), minutes=120)
    bench.book.move_to("bx-14", "leased", NOW - timedelta(minutes=5), actor="team-a")
    bench.book.move_to("bx-14", "programming", NOW - timedelta(minutes=4), actor="team-a")
    report = bench.recover()
    assert report.boards_returned == ("bx-14",)
    assert bench.book.state("bx-14").state == "leased"


def test_a_journal_line_for_a_job_nobody_has_is_reported_rather_than_ignored(tmp_path):
    bench = Bench(tmp_path)
    bench.journal.append("job-09999", "started", NOW - timedelta(minutes=5), board="bx-14")
    report = bench.recover()
    assert report.unknown_jobs == ("job-09999",)
    assert bench.journal.unfinished() == ()


def test_a_report_describes_what_it_did(tmp_path):
    bench = Bench(tmp_path)
    bench.start_job(board="bx-15", mode="flash-update")
    described = bench.recover().describe()
    assert "closed job-00001" in described
    assert "faulted bx-15" in described


def test_a_job_that_already_failed_is_not_closed_again(tmp_path):
    bench = Bench(tmp_path)
    job = bench.start_job()
    bench.jobs.update(bench.jobs.job(job.id).failed(NOW, "cable", retryable=False))
    report = bench.recover()
    assert report.jobs_closed == ()
    assert bench.jobs.job(job.id).error == "cable"
