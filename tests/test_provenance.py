import pytest

from fpga.artifacts.provenance import Provenance, ProvenanceLog
from fpga.artifacts.registry import ArtifactStore
from fpga.bitstream.bitfile import build_bitfile
from fpga.bitstream.image import read_bitstream
from fpga.bitstream.packets import SYNC_WORD, type1, type2, words_to_bytes
from fpga.errors import NotFound, ValidationError

REVISION = "9f2c11a4b7d3e5061728394a5b6c7d8e9f012345"


def image(frames=8):
    data = words_to_bytes(
        [
            SYNC_WORD,
            type1(opcode=2, register=12, word_count=1),
            0x03631093,
            type1(opcode=2, register=2, word_count=0),
            type2(opcode=2, word_count=frames),
            *range(frames),
            type1(opcode=2, register=4, word_count=1),
            5,
        ]
    )
    return read_bitstream(
        build_bitfile(
            design="radar_frontend",
            part="7a100tfgg484",
            date="2025/05/26",
            time="11:00:00",
            data=data,
        )
    )


def build(digest, **changes):
    fields = {
        "digest": digest,
        "design": "radar_frontend",
        "source_revision": REVISION,
        "build_id": "nightly-1841",
        "built_at": "2025-05-26T02:11:00+00:00",
        "tool_version": "2024.1",
        "constraints_digest": "c0ffee",
        "builder": "agent-04",
        "branch": "main",
    }
    fields.update(changes)
    return Provenance(**fields)


def test_a_build_records_where_it_came_from():
    provenance = build("ab" * 32)
    assert provenance.short_revision == REVISION[:12]
    assert provenance.design == "radar_frontend"
    assert provenance.branch == "main"
    assert provenance.describe() == (
        "radar_frontend from 9f2c11a4b7d3 on main (clean) build nightly-1841"
    )


def test_a_short_revision_is_still_a_revision():
    assert build("ab" * 32, source_revision="9F2C11A").source_revision == "9f2c11a"


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("source_revision", "not-a-sha"),
        ("source_revision", ""),
        ("build_id", "  "),
        ("built_at", ""),
    ],
)
def test_a_build_missing_its_facts_is_refused(field, value):
    with pytest.raises(ValidationError):
        build("ab" * 32, **{field: value})


def test_a_clean_build_with_its_constraints_can_be_redone():
    assert build("ab" * 32).reproducible


def test_a_dirty_tree_is_not_reproducible():
    assert not build("ab" * 32, dirty=True).reproducible


def test_a_build_without_a_constraints_digest_is_not_reproducible_either():
    assert not build("ab" * 32, constraints_digest="").reproducible


def test_two_builds_off_the_same_inputs_agree():
    first = build("ab" * 32)
    second = build("ab" * 32, build_id="nightly-1842", builder="agent-07")
    assert first.same_source_as(second)


def test_a_changed_constraint_makes_two_builds_disagree():
    first = build("ab" * 32)
    second = build("ab" * 32, constraints_digest="decaf1")
    assert not first.same_source_as(second)


def test_a_build_round_trips_through_json():
    provenance = build("ab" * 32)
    assert Provenance.from_json(provenance.to_json()) == provenance


def test_a_build_is_recorded_beside_its_image(tmp_path):
    store = ArtifactStore(tmp_path / "artifacts")
    record = store.put(image())
    log = ProvenanceLog(store.root)
    kept = log.record(build(record.digest))
    assert len(kept) == 1
    assert log.for_digest(record.digest)[0].build_id == "nightly-1841"


def test_recording_the_same_build_twice_changes_nothing(tmp_path):
    store = ArtifactStore(tmp_path / "artifacts")
    record = store.put(image())
    log = ProvenanceLog(store.root)
    log.record(build(record.digest))
    log.record(build(record.digest))
    assert len(log.for_digest(record.digest)) == 1


def test_two_builds_of_one_image_are_both_kept(tmp_path):
    store = ArtifactStore(tmp_path / "artifacts")
    record = store.put(image())
    log = ProvenanceLog(store.root)
    log.record(build(record.digest))
    log.record(build(record.digest, build_id="nightly-1842", source_revision="a" * 40))
    assert len(log.for_digest(record.digest)) == 2
    assert log.revisions(record.digest) == ("9f2c11a4b7d3e5061728394a5b6c7d8e9f012345", "a" * 40)


def test_a_build_from_different_inputs_is_flagged_as_a_disagreement(tmp_path):
    store = ArtifactStore(tmp_path / "artifacts")
    record = store.put(image())
    log = ProvenanceLog(store.root)
    log.record(build(record.digest))
    log.record(build(record.digest, build_id="rebuild-2", constraints_digest="decaf1"))
    assert log.disagreements(record.digest) == ("rebuild-2",)


def test_two_builds_that_agree_are_not_a_disagreement(tmp_path):
    store = ArtifactStore(tmp_path / "artifacts")
    record = store.put(image())
    log = ProvenanceLog(store.root)
    log.record(build(record.digest))
    log.record(build(record.digest, build_id="rebuild-2"))
    assert log.disagreements(record.digest) == ()


def test_a_single_build_cannot_disagree_with_anything(tmp_path):
    store = ArtifactStore(tmp_path / "artifacts")
    record = store.put(image())
    log = ProvenanceLog(store.root)
    log.record(build(record.digest))
    assert log.disagreements(record.digest) == ()


def test_recording_against_an_image_nobody_stored_is_a_not_found(tmp_path):
    log = ProvenanceLog(tmp_path / "artifacts")
    with pytest.raises(NotFound):
        log.record(build("ab" * 32))


def test_an_image_with_no_builds_recorded_has_none(tmp_path):
    store = ArtifactStore(tmp_path / "artifacts")
    record = store.put(image())
    log = ProvenanceLog(store.root)
    assert log.for_digest(record.digest) == ()
    assert log.revisions(record.digest) == ()
