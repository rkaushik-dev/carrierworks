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

REVISION = "9f2c11a6b3d4e5f60718293a4b5c6d7e8f901234"


def now():
    return datetime.now(UTC)


@pytest.fixture()
def workspace(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    database = BenchDatabase(
        BenchSettings(
            database_url=url,
            artifact_root=tmp_path / "artifacts",
            journal_root=tmp_path / "journal",
        )
    )
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


def record(run, digest, *extra, revision=REVISION, build_id="nightly-41"):
    return run(
        "--json",
        "image",
        "build",
        digest,
        "--revision",
        revision,
        "--build-id",
        build_id,
        *extra,
    )


def load(run, digest):
    """One clean load of the image onto the board."""
    run("lease", "take", "--board", "bx-14", "--holder", "team-a", "--purpose", "capture")
    run("job", "submit", "--board", "bx-14", "--image", digest, "--by", "team-a")
    run("job", "run")


def test_a_build_is_written_down(run, digest):
    body = json.loads(record(run, digest).output)
    assert body["revision"] == REVISION
    assert body["build_id"] == "nightly-41"
    assert body["builds"] == 1


def test_the_line_says_where_it_came_from(run, digest):
    result = run(
        "image",
        "build",
        digest,
        "--revision",
        REVISION,
        "--build-id",
        "nightly-41",
    )
    assert "built from 9f2c11a" in result.output


def test_a_second_build_of_the_same_bytes_is_kept_too(run, digest):
    record(run, digest, build_id="nightly-41")
    body = json.loads(record(run, digest, build_id="nightly-42").output)
    assert body["builds"] == 2


def test_a_dirty_tree_is_recorded(run, digest):
    body = json.loads(record(run, digest, "--dirty").output)
    assert body["dirty"] is True


def test_the_branch_and_builder_are_kept(run, digest):
    body = json.loads(record(run, digest, "--branch", "main", "--builder", "build-07").output)
    assert body["branch"] == "main"


def test_a_revision_that_is_not_a_git_name_is_refused(run, digest):
    result = record(run, digest, revision="yesterday")
    assert result.exit_code != 0
    assert "source revision" in result.output


def test_a_build_for_an_image_nobody_has_is_refused(run):
    assert record(run, "ab" * 32).exit_code != 0


def test_the_build_shows_up_on_the_image(run, digest):
    record(run, digest)
    body = json.loads(run("--json", "image", "show", digest).output)
    assert body["builds"] == ["nightly-41"]


def test_an_image_with_no_build_cannot_go_golden(run, digest):
    load(run, digest)
    result = run("image", "promote", digest, "golden", "--approve", "kim")
    assert result.exit_code != 0
    assert "nothing recorded where this image came from" in result.output


def test_a_build_and_three_clean_loads_get_it_there(run, digest):
    record(run, digest, "--constraints", "c0ffee", "--tool", "2024.1")
    for _ in range(3):
        load(run, digest)
    body = json.loads(
        run("--json", "image", "promote", digest, "golden", "--approve", "kim").output
    )
    assert body["channel"] == "golden"


def test_two_builds_disagreeing_about_the_source_hold_it_back(run, digest):
    record(run, digest, "--constraints", "c0ffee", build_id="nightly-41")
    record(run, digest, "--constraints", "c0ffee", build_id="nightly-42", revision="0" * 40)
    for _ in range(3):
        load(run, digest)
    result = run("image", "promote", digest, "golden", "--approve", "kim")
    assert result.exit_code != 0
