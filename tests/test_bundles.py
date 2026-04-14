import json
from datetime import UTC, datetime, timedelta

import pytest

from boardsetup import kintex
from fpga.artifacts.registry import ArtifactStore
from fpga.bitstream.bitfile import build_bitfile
from fpga.bitstream.image import read_bitstream
from fpga.bitstream.packets import SYNC_WORD, type1, type2, words_to_bytes
from fpga.errors import NotFound, ValidationError
from fpga.persistence.unit import BenchDatabase
from fpga.services import bundles, inventory, leasing, programming, registry, reports

WHEN = datetime(2026, 4, 20, 9, 0, tzinfo=UTC)

TIMING = """
| WNS(ns) | TNS(ns) | TNS Failing | TNS Total | WHS(ns) | THS(ns) | THS Failing | THS Total |
| 0.412 | 0.000 | 0 | 118423 | 0.137 | 0.000 | 0 | 118423 |
"""

UTILIZATION = """
| Slice LUTs      | 45123 |     0 |    203800 | 22.14 |
| Slice Registers | 61234 |     0 |    407600 | 15.02 |
"""


def bitfile(frames=16):
    words = [
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
    return build_bitfile(
        design="radar_frontend;Version=2024.1",
        part="7k325tffg900",
        date="2026/04/19",
        time="02:00:00",
        data=words_to_bytes(words),
    )


@pytest.fixture()
def store(tmp_path):
    return ArtifactStore(tmp_path / "artifacts")


@pytest.fixture()
def unit(store):
    database = BenchDatabase.in_memory()
    with database.unit() as open_unit:
        inventory.add_board(open_unit, kintex("bx-14"), WHEN - timedelta(days=1))
        yield open_unit


@pytest.fixture()
def digest(unit, store):
    record = registry.ingest(unit, store, read_bitstream(bitfile()), WHEN, channel="qual")
    return record.digest


def bundle(unit, digest, tmp_path, store, **rest):
    return bundles.assemble(unit, digest, tmp_path / "out", WHEN, store=store, **rest)


def test_a_bundle_carries_the_image_and_a_manifest(unit, digest, tmp_path, store):
    made = bundle(unit, digest, tmp_path, store)
    assert made.manifest_path.is_file()
    assert (made.root / "image.bin").read_bytes() == store.read(digest)


def test_the_directory_is_named_after_the_image(unit, digest, tmp_path, store):
    made = bundle(unit, digest, tmp_path, store)
    assert made.root.name == digest[:12]


def test_every_file_is_listed_in_the_manifest(unit, digest, tmp_path, store):
    made = bundle(unit, digest, tmp_path, store)
    manifest = json.loads(made.manifest_path.read_text(encoding="utf-8"))
    assert set(manifest["files"]) == set(made.files) - {"manifest.json"}


def test_the_manifest_records_what_the_image_is(unit, digest, tmp_path, store):
    made = bundle(unit, digest, tmp_path, store)
    manifest = json.loads(made.manifest_path.read_text(encoding="utf-8"))
    assert manifest["design"] == "radar_frontend"
    assert manifest["device"] == "xc7k325t"
    assert manifest["channel"] == "qual"


def test_approvals_are_carried_and_deduplicated(unit, digest, tmp_path, store):
    made = bundle(unit, digest, tmp_path, store, approvals=("kim", "sam", "kim"))
    manifest = json.loads(made.manifest_path.read_text(encoding="utf-8"))
    assert manifest["approvals"] == ["kim", "sam"]


def test_a_bundle_for_an_image_nobody_stored_is_refused(unit, tmp_path, store):
    with pytest.raises(NotFound):
        bundle(unit, "ab" * 32, tmp_path, store)


def test_a_bundle_over_a_full_directory_is_refused(unit, digest, tmp_path, store):
    bundle(unit, digest, tmp_path, store)
    with pytest.raises(ValidationError):
        bundle(unit, digest, tmp_path, store)


def test_the_loads_of_the_image_are_written_down(unit, digest, tmp_path, store):
    leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN)
    programming.submit(unit, "bx-14", digest, "jtag", "team-a", WHEN)
    made = bundle(unit, digest, tmp_path, store)
    jobs = json.loads((made.root / "jobs.json").read_text(encoding="utf-8"))
    assert made.jobs == 1
    assert jobs[0]["board"] == "bx-14"
    assert jobs[0]["state"] == "queued"


def test_an_image_nobody_has_loaded_gets_an_empty_job_list(unit, digest, tmp_path, store):
    made = bundle(unit, digest, tmp_path, store)
    assert json.loads((made.root / "jobs.json").read_text(encoding="utf-8")) == []
    assert made.jobs == 0


def test_the_reports_go_in_when_there_are_any(unit, digest, tmp_path, store):
    reports.ingest(unit, digest, WHEN, timing_text=TIMING, utilization_text=UTILIZATION)
    made = bundle(unit, digest, tmp_path, store)
    body = json.loads((made.root / "reports.json").read_text(encoding="utf-8"))
    assert made.reports == 1
    assert body["closed"] is True
    assert body["worst_slack_ps"] == 412


def test_an_image_with_no_reports_has_no_report_file(unit, digest, tmp_path, store):
    made = bundle(unit, digest, tmp_path, store)
    assert "reports.json" not in made.files
    assert made.reports == 0


def test_a_fresh_bundle_verifies(unit, digest, tmp_path, store):
    made = bundle(unit, digest, tmp_path, store)
    assert bundles.verify(made.root) == ()


def test_an_edited_file_is_caught(unit, digest, tmp_path, store):
    made = bundle(unit, digest, tmp_path, store)
    (made.root / "image.bin").write_bytes(b"not the image")
    assert bundles.verify(made.root) == ("image.bin does not match the manifest",)


def test_a_missing_file_is_caught(unit, digest, tmp_path, store):
    made = bundle(unit, digest, tmp_path, store)
    (made.root / "jobs.json").unlink()
    assert bundles.verify(made.root) == ("jobs.json is missing",)


def test_verifying_something_that_is_not_a_bundle_is_an_error(tmp_path):
    with pytest.raises(NotFound):
        bundles.verify(tmp_path)


def test_the_bundle_reads_as_one_line(unit, digest, tmp_path, store):
    assert "1 loads" not in bundle(unit, digest, tmp_path, store).describe()
