from datetime import UTC, datetime, timedelta

import pytest

from boardsetup import kintex
from fpga.artifacts.registry import ArtifactStore
from fpga.bitstream.bitfile import build_bitfile
from fpga.bitstream.image import read_bitstream
from fpga.bitstream.packets import SYNC_WORD, type1, type2, words_to_bytes
from fpga.config import BenchSettings
from fpga.inventory.book import BoardBook
from fpga.inventory.faults import FaultLog
from fpga.jobs.flasher import FakeFlash
from fpga.jobs.journal import JobJournal
from fpga.jobs.models import ProgramJob
from fpga.jobs.runner import JobRunner, flash_layout
from fpga.jtag.chain import ScanChain
from fpga.jtag.session import ScanSession
from fpga.jtag.simulator import ChainSimulator, SimulatedDevice
from fpga.leases.store import LeaseStore

NOW = datetime(2025, 10, 24, 9, 0, tzinfo=UTC)


def config_words(frames=64):
    return words_to_bytes(
        [
            0xFFFFFFFF,
            SYNC_WORD,
            type1(opcode=2, register=12, word_count=1),
            0x03651093,
            type1(opcode=2, register=2, word_count=0),
            type2(opcode=2, word_count=frames),
            *range(frames),
            type1(opcode=2, register=4, word_count=1),
            5,
        ]
    )


def image(frames=64):
    return read_bitstream(
        build_bitfile(
            design="radar_frontend;Version=2024.1",
            part="7k325tffg900",
            date="2025/10/20",
            time="02:00:00",
            data=config_words(frames),
        ),
        source="radar_frontend.bit",
    )


class Bench:
    def __init__(self, tmp_path, *, channel="qual", deaf=False, weak=False, settings=None):
        self.book = BoardBook(when=NOW - timedelta(days=1))
        self.book.add(kintex("bx-14", serial="210308AB1234", outlet=7))
        self.store = ArtifactStore(tmp_path / "artifacts")
        self.record = self.store.put(image(), channel=channel)
        self.leases = LeaseStore()
        self.journal = JobJournal(tmp_path / "journal.jsonl")
        self.faults = FaultLog()
        self.flash = FakeFlash(spec=self.book.board("bx-14").flash_spec())
        if weak:
            # a byte inside the update region, which is where the job writes
            self.flash.weak_bytes = {self.flash.spec.capacity_bytes // 2 + 16}
        self.deaf = deaf
        self.runner = JobRunner(
            self.store,
            self.book,
            self.leases,
            self.journal,
            self.faults,
            settings=settings or BenchSettings(),
        )
        self.lease = self.leases.acquire("bx-14", "team-a", "capture", NOW)
        self.book.move_to("bx-14", "leased", NOW, actor="team-a")

    def session_for(self, board):
        chain = ScanChain.of_devices("xc7k325t")
        if self.deaf:

            class Deaf(SimulatedDevice):
                def take_config(self, written):
                    return None

            sim = ChainSimulator([Deaf(label="u1", idcode=0x03651093, ir_length=6)])
        else:
            sim = ChainSimulator.for_chain(chain)
        return ScanSession(sim, chain)

    def flash_for(self, board):
        return self.flash

    def job(self, **changes):
        fields = {
            "id": "job-00001",
            "board": "bx-14",
            "digest": self.record.digest,
            "mode": "jtag",
            "requested_by": "team-a",
            "created_at": NOW,
        }
        fields.update(changes)
        return ProgramJob(**fields)

    def run(self, job=None, **rest):
        return self.runner.run(
            job or self.job(),
            NOW,
            session_for=self.session_for,
            flash_for=self.flash_for,
            **rest,
        )


def test_a_good_jtag_job_passes(tmp_path):
    bench = Bench(tmp_path)
    result = bench.run()
    assert result.passed
    assert "done" in result.detail
    assert bench.book.state("bx-14").state == "leased"


def test_a_pass_is_journalled_with_the_board(tmp_path):
    bench = Bench(tmp_path)
    bench.run()
    events = [entry.event for entry in bench.journal.for_job("job-00001")]
    assert events == ["started", "passed"]
    assert bench.journal.unfinished() == ()


def test_the_board_is_marked_programming_while_the_job_runs(tmp_path):
    bench = Bench(tmp_path)
    seen = []

    original = bench.session_for

    def watching(board):
        seen.append(bench.book.state(board).state)
        return original(board)

    bench.runner.run(bench.job(), NOW, session_for=watching, flash_for=bench.flash_for)
    assert seen == ["programming"]


def test_a_job_that_fails_its_gates_never_touches_the_board(tmp_path):
    bench = Bench(tmp_path, channel="dev")
    result = bench.run(bench.job(mode="flash-update"), timing_closed=True)
    assert not result.passed
    assert not result.will_retry
    assert bench.book.state("bx-14").state == "leased"
    assert "takes qual or golden" in result.detail


def test_a_transient_failure_comes_back_around(tmp_path):
    bench = Bench(tmp_path, deaf=True)
    result = bench.run()
    assert result.will_retry
    assert result.retry_in_seconds == 15
    assert bench.faults.count("bx-14", NOW, timedelta(hours=1)) == 1


def test_the_backoff_grows_with_the_attempt(tmp_path):
    bench = Bench(tmp_path, deaf=True)
    assert bench.runner.backoff_seconds(1) == 15
    assert bench.runner.backoff_seconds(3) == 45


def test_a_job_that_runs_out_of_attempts_stops_retrying(tmp_path):
    bench = Bench(tmp_path, deaf=True)
    job = bench.job()
    for _ in range(3):
        result = bench.run(job)
        job = result.job
    assert job.state == "failed"
    assert job.attempts == 3
    assert not result.will_retry


def test_a_failed_jtag_job_leaves_the_board_leased_rather_than_faulted(tmp_path):
    bench = Bench(tmp_path, deaf=True)
    bench.run()
    assert bench.book.state("bx-14").state == "leased"


def test_a_flash_job_writes_and_verifies(tmp_path):
    bench = Bench(tmp_path)
    result = bench.run(bench.job(mode="flash-update"), timing_closed=True)
    assert result.passed
    assert "verified" in result.detail
    start = flash_layout(bench.flash.spec).region("update").start
    assert bench.flash.read(start, 16) == bench.store.read(bench.record.digest)[:16]


def test_a_flash_job_that_fails_leaves_the_board_faulted(tmp_path):
    bench = Bench(tmp_path, weak=True)
    result = bench.run(bench.job(mode="flash-update"), timing_closed=True)
    assert not result.passed
    assert result.faulted_board
    assert bench.book.state("bx-14").state == "faulted"


def test_a_verify_failure_is_not_retried(tmp_path):
    bench = Bench(tmp_path, weak=True)
    result = bench.run(bench.job(mode="flash-update"), timing_closed=True)
    assert not result.will_retry
    assert bench.faults.for_board("bx-14")[0].kind == "verify"


def test_a_failure_is_journalled_with_its_code(tmp_path):
    bench = Bench(tmp_path, deaf=True)
    bench.run()
    last = bench.journal.last_event("job-00001")
    assert last.event == "failed"
    assert last.detail["code"] == "programming"


def test_wear_is_counted_when_a_flash_job_runs(tmp_path):
    from fpga.flash.wear import WearLedger

    bench = Bench(tmp_path)
    bench.runner.wear = WearLedger(tmp_path / "wear.json")
    bench.run(bench.job(mode="flash-update"), timing_closed=True)
    assert bench.runner.wear.total("bx-14") > 0


def test_a_job_for_an_image_nobody_stored_is_a_not_found(tmp_path):
    bench = Bench(tmp_path)
    from fpga.errors import NotFound

    with pytest.raises(NotFound):
        bench.run(bench.job(digest="ab" * 32))


def test_a_jtag_job_with_no_transport_fails_rather_than_pretending(tmp_path):
    bench = Bench(tmp_path)
    result = bench.runner.run(bench.job(), NOW, session_for=None, flash_for=bench.flash_for)
    assert not result.passed
    assert not result.will_retry


def test_the_result_describes_itself(tmp_path):
    bench = Bench(tmp_path, deaf=True)
    described = bench.run().describe()
    assert described.startswith("job-00001 queued: ")
    assert described.endswith("retry in 15s")
