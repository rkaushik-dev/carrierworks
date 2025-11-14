from datetime import UTC, datetime, timedelta

from fpga.artifacts.channels import evaluate_promotion
from fpga.artifacts.provenance import Provenance, ProvenanceLog
from fpga.artifacts.registry import ArtifactRecord, ArtifactStore
from fpga.bitstream.bitfile import build_bitfile
from fpga.bitstream.image import read_bitstream
from fpga.bitstream.packets import SYNC_WORD, type1, type2, words_to_bytes
from fpga.jobs.store import JobStore
from fpga.reports.baselines import BaselineBook, BuildReport
from fpga.reports.evidence import evidence_for, load_counts, timing_for
from fpga.reports.timing import TimingSummary
from fpga.reports.utilization import SiteUsage, Utilization

NOW = datetime(2025, 11, 14, 9, 0, tzinfo=UTC)
DIGEST = "ab" * 32


def timing(wns=400, failing=0):
    return TimingSummary(
        wns_ps=wns,
        tns_ps=0,
        setup_failing=failing,
        setup_total=1000,
        whs_ps=100,
        ths_ps=0,
        hold_failing=0,
        hold_total=1000,
    )


def utilization():
    return Utilization(rows=(SiteUsage(name="Slice LUTs", used=1000, available=203800),))


def report(digest=DIGEST, **changes):
    return BuildReport(
        digest=digest,
        design="radar_frontend",
        at=NOW,
        timing=changes.get("timing", timing()),
        utilization=utilization(),
    )


def provenance_log(tmp_path, **changes):
    words = words_to_bytes(
        [
            SYNC_WORD,
            type1(opcode=2, register=12, word_count=1),
            0x03631093,
            type1(opcode=2, register=2, word_count=0),
            type2(opcode=2, word_count=4),
            1,
            2,
            3,
            4,
            type1(opcode=2, register=4, word_count=1),
            5,
        ]
    )
    image = read_bitstream(
        build_bitfile(
            design="radar_frontend",
            part="7a100tfgg484",
            date="d",
            time="t",
            data=words,
        )
    )
    store = ArtifactStore(tmp_path / "artifacts")
    record = store.put(image)
    log = ProvenanceLog(store.root)
    fields = {
        "digest": record.digest,
        "design": "radar_frontend",
        "source_revision": "9f2c11a",
        "build_id": "nightly-1",
        "built_at": NOW.isoformat(),
        "constraints_digest": "c0ffee",
    }
    fields.update(changes)
    log.record(Provenance(**fields))
    return log, record


def jobs_with(*outcomes, digest=DIGEST):
    store = JobStore()
    for index, outcome in enumerate(outcomes):
        job = store.submit("bx-14", digest, "jtag", "team-a", NOW + timedelta(minutes=index))
        running = job.start(NOW + timedelta(minutes=index))
        if outcome == "passed":
            store.update(running.passed(NOW + timedelta(minutes=index, seconds=30)))
        elif outcome == "gate":
            store.update(running.failed(NOW, "refused by the gates", code="gate"))
        else:
            store.update(running.failed(NOW, "the device did not come up", code="programming"))
    return store


def test_an_image_with_no_report_has_no_timing_answer():
    assert timing_for(BaselineBook(), DIGEST) == (None, None)


def test_a_closed_build_reports_its_slack():
    book = BaselineBook()
    book.record(report())
    assert timing_for(book, DIGEST) == (True, 100)


def test_a_failed_build_says_so():
    book = BaselineBook()
    book.record(report(timing=timing(wns=-100, failing=3)))
    closed, slack = timing_for(book, DIGEST)
    assert closed is False
    assert slack == -100


def test_loads_are_counted_from_the_job_store():
    assert load_counts(jobs_with("passed", "passed", "failed"), DIGEST) == (2, 1)


def test_a_job_for_another_image_is_not_counted():
    store = jobs_with("passed")
    other = store.submit("bx-15", "cd" * 32, "jtag", "team-a", NOW)
    store.update(other.start(NOW).passed(NOW))
    assert load_counts(store, DIGEST) == (1, 0)


def test_a_job_refused_by_the_gates_is_not_a_failure_of_the_image():
    assert load_counts(jobs_with("passed", "gate"), DIGEST) == (1, 0)


def test_an_unfinished_job_counts_neither_way():
    store = JobStore()
    job = store.submit("bx-14", DIGEST, "jtag", "team-a", NOW)
    store.update(job.start(NOW))
    assert load_counts(store, DIGEST) == (0, 0)


def test_the_evidence_pulls_all_four_sources_together(tmp_path):
    log, record = provenance_log(tmp_path)
    book = BaselineBook()
    book.record(report(digest=record.digest))
    evidence = evidence_for(
        record.digest,
        baselines=book,
        jobs=jobs_with("passed", "passed", "passed", digest=record.digest),
        provenance=log,
        approvals=("kmoyer", "kmoyer"),
    )
    assert evidence.timing_closed is True
    assert evidence.successful_loads == 3
    assert evidence.approvals == ("kmoyer",)
    assert evidence.reproducible


def test_the_evidence_is_what_promotion_reads(tmp_path):
    log, record = provenance_log(tmp_path)
    book = BaselineBook()
    book.record(report(digest=record.digest))
    evidence = evidence_for(
        record.digest,
        baselines=book,
        jobs=jobs_with("passed", "passed", "passed", digest=record.digest),
        provenance=log,
        approvals=("kmoyer",),
    )
    qualified = ArtifactRecord(
        digest=record.digest,
        design="radar_frontend",
        device="xc7a100t",
        length=record.length,
        kind="full",
        channel="qual",
    )
    assert evaluate_promotion(qualified, "golden", evidence).allowed


def test_a_dirty_build_stops_the_same_promotion(tmp_path):
    log, record = provenance_log(tmp_path, dirty=True)
    book = BaselineBook()
    book.record(report(digest=record.digest))
    evidence = evidence_for(
        record.digest,
        baselines=book,
        jobs=jobs_with("passed", "passed", "passed", digest=record.digest),
        provenance=log,
        approvals=("kmoyer",),
    )
    qualified = ArtifactRecord(
        digest=record.digest,
        design="radar_frontend",
        device="xc7a100t",
        length=record.length,
        kind="full",
        channel="qual",
    )
    decision = evaluate_promotion(qualified, "golden", evidence)
    assert not decision.allowed
    assert "cannot be reproduced" in decision.blockers[0]


def test_evidence_without_a_provenance_log_still_builds():
    evidence = evidence_for(DIGEST, baselines=BaselineBook(), jobs=JobStore())
    assert evidence.provenance == ()
    assert evidence.timing_closed is None
    assert evidence.load_attempts == 0
