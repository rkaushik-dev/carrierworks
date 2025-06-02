import pytest

from fpga.artifacts.channels import (
    CHANNELS,
    PromotionEvidence,
    channel_rank,
    evaluate_promotion,
)
from fpga.artifacts.provenance import Provenance
from fpga.artifacts.registry import ArtifactRecord
from fpga.errors import ValidationError

DIGEST = "ab" * 32


def record(channel="dev", kind="full"):
    return ArtifactRecord(
        digest=DIGEST,
        design="radar_frontend",
        device="xc7a100t",
        length=3_000_000,
        kind=kind,
        channel=channel,
    )


def provenance(**changes):
    fields = {
        "digest": DIGEST,
        "design": "radar_frontend",
        "source_revision": "9f2c11a",
        "build_id": "nightly-1841",
        "built_at": "2025-06-01T02:00:00+00:00",
        "constraints_digest": "c0ffee",
    }
    fields.update(changes)
    return Provenance(**fields)


def good_evidence(**changes):
    fields = {
        "timing_closed": True,
        "worst_slack_ps": 147,
        "successful_loads": 4,
        "approvals": ("kmoyer",),
        "provenance": (provenance(),),
    }
    fields.update(changes)
    return PromotionEvidence(**fields)


def test_the_channels_are_ordered_by_trust():
    assert CHANNELS == ("dev", "qual", "golden")
    assert channel_rank("dev") < channel_rank("qual") < channel_rank("golden")


def test_a_channel_nobody_has_is_refused():
    with pytest.raises(ValidationError):
        channel_rank("staging")


def test_a_good_image_gets_into_qual():
    decision = evaluate_promotion(record(), "qual", good_evidence())
    assert decision.allowed
    assert decision.blockers == ()
    assert decision.describe() == "abababababab may go from dev to qual"


def test_an_image_with_no_timing_report_does_not():
    decision = evaluate_promotion(record(), "qual", good_evidence(timing_closed=None))
    assert not decision.allowed
    assert "no timing report has been read for this image" in decision.blockers


def test_a_design_that_missed_timing_does_not():
    decision = evaluate_promotion(record(), "qual", good_evidence(timing_closed=False))
    assert "the design did not close timing" in decision.blockers


def test_a_partial_image_cannot_be_qualified():
    decision = evaluate_promotion(record(kind="partial"), "qual", good_evidence())
    assert "a partial image cannot be qualified on its own" in decision.blockers


def test_an_image_with_no_provenance_cannot_be_qualified():
    decision = evaluate_promotion(record(), "qual", good_evidence(provenance=()))
    assert "nothing recorded where this image came from" in decision.blockers


def test_a_dirty_build_cannot_be_qualified():
    evidence = good_evidence(provenance=(provenance(dirty=True),))
    decision = evaluate_promotion(record(), "qual", evidence)
    assert "the build cannot be reproduced from a clean tree" in decision.blockers


def test_one_dirty_build_among_several_is_still_a_blocker():
    evidence = good_evidence(provenance=(provenance(), provenance(build_id="local-1", dirty=True)))
    decision = evaluate_promotion(record(), "qual", evidence)
    assert not decision.allowed


def test_every_blocker_is_listed_rather_than_the_first():
    decision = evaluate_promotion(record(kind="partial"), "qual", PromotionEvidence())
    assert len(decision.blockers) == 3


def test_a_qualified_image_with_approval_and_a_soak_goes_golden():
    decision = evaluate_promotion(record(channel="qual"), "golden", good_evidence())
    assert decision.allowed


def test_golden_needs_somebody_to_say_so():
    decision = evaluate_promotion(record(channel="qual"), "golden", good_evidence(approvals=()))
    assert "nobody has approved this image" in decision.blockers


def test_golden_needs_the_image_to_have_run_a_few_times():
    decision = evaluate_promotion(
        record(channel="qual"),
        "golden",
        good_evidence(successful_loads=1),
    )
    assert "only 1 clean loads on the bench, 3 are wanted" in decision.blockers


def test_the_soak_count_is_settable():
    decision = evaluate_promotion(
        record(channel="qual"),
        "golden",
        good_evidence(successful_loads=1),
        soak_loads=1,
    )
    assert decision.allowed


def test_a_failed_load_blocks_golden_however_many_worked():
    decision = evaluate_promotion(
        record(channel="qual"),
        "golden",
        good_evidence(successful_loads=40, failed_loads=1),
    )
    assert "1 loads of this image have failed" in decision.blockers


def test_builds_that_disagree_block_golden():
    decision = evaluate_promotion(
        record(channel="qual"),
        "golden",
        good_evidence(disagreements=("rebuild-2",)),
    )
    assert "two builds of this image disagree about their source" in decision.blockers


def test_golden_still_has_to_meet_the_qual_conditions():
    decision = evaluate_promotion(
        record(channel="qual"),
        "golden",
        good_evidence(timing_closed=False),
    )
    assert "the design did not close timing" in decision.blockers


def test_promotion_goes_one_channel_at_a_time():
    decision = evaluate_promotion(record(), "golden", good_evidence())
    assert not decision.allowed
    assert decision.blockers == ("promotion goes one channel at a time, dev to golden skips one",)


def test_promoting_to_where_it_already_is_says_so():
    decision = evaluate_promotion(record(channel="qual"), "qual", good_evidence())
    assert decision.blockers == ("the image is already in qual",)


def test_demotion_is_always_allowed():
    decision = evaluate_promotion(record(channel="golden"), "dev", PromotionEvidence())
    assert decision.allowed
    assert decision.to_channel == "dev"


def test_evidence_counts_its_attempts():
    evidence = good_evidence(successful_loads=4, failed_loads=2)
    assert evidence.load_attempts == 6
    assert evidence.reproducible


def test_evidence_with_no_builds_is_not_reproducible():
    assert not PromotionEvidence().reproducible


def test_a_decision_with_blockers_describes_them():
    decision = evaluate_promotion(record(), "qual", PromotionEvidence())
    assert decision.describe().startswith("abababababab cannot go from dev to qual: ")
