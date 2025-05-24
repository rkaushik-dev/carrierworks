import pytest

from fpga.artifacts.registry import ArtifactRecord, ArtifactStore
from fpga.bitstream.bitfile import build_bitfile
from fpga.bitstream.image import read_bitstream
from fpga.bitstream.packets import SYNC_WORD, type1, type2, words_to_bytes
from fpga.errors import NotFound, ValidationError


def config(idcode=0x03631093, start=True, frames=8):
    words = [
        0xFFFFFFFF,
        SYNC_WORD,
        type1(opcode=2, register=12, word_count=1),
        idcode,
        type1(opcode=2, register=2, word_count=0),
        type2(opcode=2, word_count=frames),
        *range(frames),
    ]
    if start:
        words += [type1(opcode=2, register=4, word_count=1), 5]
    return words_to_bytes(words)


def image(design="radar_frontend", part="7a100tfgg484", **kwargs):
    return read_bitstream(
        build_bitfile(
            design=f"{design};Version=2024.1",
            part=part,
            date="2025/05/20",
            time="08:00:00",
            data=config(**kwargs),
        ),
        source=f"{design}.bit",
    )


def store(tmp_path):
    return ArtifactStore(tmp_path / "artifacts")


def test_storing_an_image_gives_a_record(tmp_path):
    record = store(tmp_path).put(image())
    assert record.design == "radar_frontend"
    assert record.device == "xc7a100t"
    assert record.kind == "full"
    assert record.channel == "dev"
    assert record.tool_version == "2024.1"
    assert record.source == "radar_frontend.bit"
    assert record.stored_at


def test_the_bytes_that_come_back_are_the_configuration_data(tmp_path):
    keeper = store(tmp_path)
    live = image()
    record = keeper.put(live)
    assert keeper.read(record.digest) == live.header.data


def test_storing_the_same_image_twice_is_one_entry(tmp_path):
    keeper = store(tmp_path)
    first = keeper.put(image())
    second = keeper.put(image())
    assert first.digest == second.digest
    assert len(keeper.records()) == 1


def test_two_different_images_are_two_entries(tmp_path):
    keeper = store(tmp_path)
    keeper.put(image())
    keeper.put(image(frames=9))
    assert len(keeper.records()) == 2


def test_a_partial_image_is_recorded_as_one(tmp_path):
    record = store(tmp_path).put(image(start=False))
    assert record.kind == "partial"
    assert record.is_partial


def test_a_missing_artifact_is_a_not_found(tmp_path):
    keeper = store(tmp_path)
    with pytest.raises(NotFound):
        keeper.read("ab" * 32)
    with pytest.raises(NotFound):
        keeper.record_for("ab" * 32)
    assert not keeper.has("ab" * 32)


def test_records_come_back_by_design(tmp_path):
    keeper = store(tmp_path)
    keeper.put(image(design="radar_frontend"))
    keeper.put(image(design="beamformer", frames=9))
    assert [record.design for record in keeper.for_design("Radar Frontend")] == ["radar_frontend"]
    assert keeper.for_design("nothing_here") == ()


def test_a_record_can_be_moved_to_another_channel(tmp_path):
    keeper = store(tmp_path)
    record = keeper.put(image())
    keeper.update(record.with_channel("qual"))
    assert keeper.record_for(record.digest).channel == "qual"


def test_updating_something_that_is_not_stored_is_a_not_found(tmp_path):
    record = ArtifactRecord.of(image())
    with pytest.raises(NotFound):
        store(tmp_path).update(record)


def test_tags_are_sorted_and_deduplicated(tmp_path):
    record = ArtifactRecord.of(image()).tagged("Nightly", "nightly", "keep")
    assert record.tags == ("keep", "nightly")


def test_a_record_round_trips_through_json():
    record = ArtifactRecord.of(image()).tagged("keep")
    assert ArtifactRecord.from_json(record.to_json()) == record


def test_a_record_describes_itself(tmp_path):
    record = store(tmp_path).put(image())
    assert record.describe().startswith("radar_frontend ")
    assert "[dev]" in record.describe()
    assert record.short_digest == record.digest[:12]


@pytest.mark.parametrize(
    ("field", "value"),
    [("digest", "nope"), ("kind", "sideways"), ("length", 0)],
)
def test_a_nonsense_record_is_refused(field, value):
    fields = {
        "digest": "ab" * 32,
        "design": "radar_frontend",
        "device": "xc7a100t",
        "length": 100,
        "kind": "full",
    }
    fields[field] = value
    with pytest.raises(ValidationError):
        ArtifactRecord(**fields)


def test_removing_an_artifact_takes_the_image_with_it(tmp_path):
    keeper = store(tmp_path)
    record = keeper.put(image())
    keeper.remove(record.digest)
    assert not keeper.has(record.digest)
    assert keeper.records() == ()
    with pytest.raises(NotFound):
        keeper.remove(record.digest)


def test_the_store_spreads_digests_across_folders(tmp_path):
    keeper = store(tmp_path)
    record = keeper.put(image())
    path = keeper.path_for(record.digest)
    assert path.parent.name == record.digest[2:]
    assert path.parent.parent.name == record.digest[:2]


def test_a_digest_that_is_too_short_to_be_one_is_refused(tmp_path):
    with pytest.raises(ValidationError):
        store(tmp_path).has("ab")
