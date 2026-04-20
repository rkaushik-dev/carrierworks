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
def workspace(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    settings = BenchSettings(
        database_url=url,
        artifact_root=tmp_path / "artifacts",
        journal_root=tmp_path / "journal",
    )
    database = BenchDatabase(settings)
    database.create_schema()
    with database.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), now() - timedelta(days=1))
    (tmp_path / "radar.bit").write_bytes(bitfile())
    (tmp_path / "timing.rpt").write_text(TIMING, encoding="utf-8")
    (tmp_path / "usage.rpt").write_text(UTILIZATION, encoding="utf-8")
    return tmp_path, url


@pytest.fixture()
def run(workspace, monkeypatch):
    root, url = workspace
    monkeypatch.setenv("FPGA_ARTIFACT_ROOT", str(root / "artifacts"))
    monkeypatch.setenv("FPGA_JOURNAL_ROOT", str(root / "journal"))
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


def take(run):
    return run(
        "lease",
        "take",
        "--board",
        "bx-14",
        "--holder",
        "team-a",
        "--purpose",
        "capture",
    )


def submit(run, digest, mode="jtag"):
    return run(
        "job",
        "submit",
        "--board",
        "bx-14",
        "--image",
        digest,
        "--mode",
        mode,
        "--by",
        "team-a",
    )


def test_a_job_goes_on_the_queue(run, digest):
    result = submit(run, digest)
    assert result.exit_code == 0
    assert "job-00001 queued" in result.output


def test_a_job_for_an_image_nobody_has_is_an_error(run):
    result = submit(run, "ab" * 32)
    assert result.exit_code != 0
    assert "no artifact known" in result.output


def test_jobs_are_listed_with_their_state(run, digest):
    submit(run, digest)
    result = run("job", "list")
    assert "JOB" in result.output
    assert "queued" in result.output


def test_the_listing_can_be_narrowed_to_a_board(run, digest):
    submit(run, digest)
    body = json.loads(run("--json", "job", "list", "--board", "bx-14").output)
    assert [entry["job"] for entry in body] == ["job-00001"]


def test_running_with_nothing_ready_says_so(run):
    assert "nothing is ready to run" in run("job", "run").output


def test_a_dry_run_passes_against_the_simulator(run, digest):
    take(run)
    submit(run, digest)
    result = run("job", "run")
    assert result.exit_code == 0
    assert "passed" in result.output
    assert "done" in result.output


def test_a_dry_run_leaves_the_board_leased_rather_than_programming(run, digest):
    take(run)
    submit(run, digest)
    run("job", "run")
    assert json.loads(run("--json", "boards", "show", "bx-14").output)["state"] == "leased"


def test_a_job_on_a_board_nobody_holds_is_refused_by_the_gates(run, digest):
    submit(run, digest)
    result = run("--json", "job", "run")
    body = json.loads(result.output)
    assert body["state"] == "failed"
    assert "nobody holds a lease" in body["detail"]


def test_a_flash_job_runs_against_the_fake_part(run, digest):
    take(run)
    submit(run, digest, mode="flash-update")
    body = json.loads(run("--json", "job", "run").output)
    assert body["state"] == "passed"
    assert "verified" in body["detail"]


def test_a_golden_write_of_a_qualified_image_is_refused(run, digest):
    take(run)
    submit(run, digest, mode="flash-golden")
    body = json.loads(run("--json", "job", "run").output)
    assert body["state"] == "failed"
    assert "golden images" in body["detail"]


def test_a_run_can_be_asked_for_by_job_id(run, digest):
    take(run)
    submit(run, digest)
    result = run("job", "run", "job-00001")
    assert "job-00001 passed" in result.output


def test_the_journal_records_the_run(run, digest, workspace):
    root, _ = workspace
    take(run)
    submit(run, digest)
    run("job", "run")
    lines = (root / "journal" / "jobs.jsonl").read_text(encoding="utf-8").splitlines()
    assert any('"event": "started"' in line for line in lines)
    assert any('"event": "passed"' in line for line in lines)


def test_a_cancelled_job_is_not_offered(run, digest):
    submit(run, digest)
    result = run("job", "cancel", "job-00001", "--reason", "board went away")
    assert "job-00001 cancelled" in result.output
    assert "nothing is ready to run" in run("job", "run").output


def test_a_second_job_on_one_board_waits_for_the_first(run, digest):
    take(run)
    submit(run, digest)
    submit(run, digest)
    body = json.loads(run("--json", "job", "list", "--state", "queued").output)
    assert len(body) == 2
    run("job", "run")
    after = json.loads(run("--json", "job", "list").output)
    assert [entry["state"] for entry in after] == ["passed", "queued"]
