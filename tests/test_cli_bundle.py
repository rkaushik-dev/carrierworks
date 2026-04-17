import json
from datetime import UTC, datetime, timedelta

import pytest
from click.testing import CliRunner

from boardsetup import kintex
from fpga.cli import images as image_commands  # noqa: F401  (registers the commands)
from fpga.cli import jobs as job_commands  # noqa: F401  (registers the commands)
from fpga.cli import leases as lease_commands  # noqa: F401  (registers the commands)
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory
from images import TIMING, UTILIZATION, bitfile


def now():
    return datetime.now(UTC)


@pytest.fixture()
def workspace(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    database = BenchDatabase(BenchSettings(database_url=url, artifact_root=tmp_path / "artifacts"))
    database.create_schema()
    with database.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), now() - timedelta(days=1))
    (tmp_path / "radar.bit").write_bytes(bitfile())
    (tmp_path / "timing.rpt").write_text(TIMING, encoding="utf-8")
    (tmp_path / "usage.rpt").write_text(UTILIZATION, encoding="utf-8")
    monkeypatch.setenv("FPGA_ARTIFACT_ROOT", str(tmp_path / "artifacts"))
    monkeypatch.setenv("FPGA_JOURNAL_ROOT", str(tmp_path / "journal"))
    return tmp_path, url


@pytest.fixture()
def run(workspace):
    _, url = workspace
    runner = CliRunner()

    def invoke(*args):
        return runner.invoke(cli, ["--database", url, *args], catch_exceptions=False)

    return invoke


@pytest.fixture()
def digest(run, workspace):
    root, _ = workspace
    result = run(
        "--json",
        "image",
        "upload",
        str(root / "radar.bit"),
        "--channel",
        "qual",
        "--timing",
        str(root / "timing.rpt"),
        "--utilization",
        str(root / "usage.rpt"),
    )
    return json.loads(result.output)["digest"]


def make(run, workspace, digest, *extra):
    root, _ = workspace
    return run("--json", "image", "bundle", digest, "--into", str(root / "out"), *extra)


def test_a_bundle_is_written_where_it_was_asked_for(run, workspace, digest):
    body = json.loads(make(run, workspace, digest).output)
    root, _ = workspace
    assert body["root"] == str(root / "out" / digest[:12])
    assert "manifest.json" in body["files"]


def test_the_bundle_carries_the_reports_that_were_read(run, workspace, digest):
    body = json.loads(make(run, workspace, digest).output)
    assert body["reports"] == 1
    assert "reports.json" in body["files"]


def test_the_bundle_records_who_signed_it_off(run, workspace, digest):
    body = json.loads(make(run, workspace, digest, "--approved-by", "kim").output)
    root, _ = workspace
    manifest = json.loads((root / "out" / digest[:12] / "manifest.json").read_text("utf-8"))
    assert manifest["approvals"] == ["kim"]
    assert body["manifest_digest"]


def test_the_loads_of_the_image_are_in_the_bundle(run, workspace, digest):
    run("lease", "take", "--board", "bx-14", "--holder", "team-a", "--purpose", "capture")
    run("job", "submit", "--board", "bx-14", "--image", digest, "--by", "team-a")
    body = json.loads(make(run, workspace, digest).output)
    assert body["jobs"] == 1


def test_bundling_an_image_nobody_has_is_an_error(run, workspace):
    result = make(run, workspace, "ab" * 32)
    assert result.exit_code != 0


def test_bundling_twice_into_the_same_place_is_refused(run, workspace, digest):
    make(run, workspace, digest)
    assert make(run, workspace, digest).exit_code != 0


def test_a_fresh_bundle_checks_out(run, workspace, digest):
    root, _ = workspace
    make(run, workspace, digest)
    result = run("image", "check-bundle", str(root / "out" / digest[:12]))
    assert result.exit_code == 0
    assert "matches its manifest" in result.output


def test_an_edited_bundle_is_caught(run, workspace, digest):
    root, _ = workspace
    make(run, workspace, digest)
    (root / "out" / digest[:12] / "image.bin").write_bytes(b"not the image")
    result = run("image", "check-bundle", str(root / "out" / digest[:12]))
    assert result.exit_code == 2
    assert "image.bin does not match" in result.output


def test_checking_something_that_is_not_a_bundle_is_an_error(run, workspace):
    root, _ = workspace
    assert run("image", "check-bundle", str(root)).exit_code != 0
