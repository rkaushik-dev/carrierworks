import json
from datetime import UTC, datetime, timedelta

import pytest
from click.testing import CliRunner

from boardsetup import artix, kintex
from fpga.artifacts.registry import ArtifactStore
from fpga.bitstream.image import read_bitstream
from fpga.cli import queue as queue_commands  # noqa: F401  (registers the commands)
from fpga.cli import worker as worker_commands  # noqa: F401  (registers the commands)
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory, leasing, programming, registry
from images import bitfile


def now():
    return datetime.now(UTC)


@pytest.fixture()
def workspace(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    settings = BenchSettings(
        database_url=url,
        artifact_root=tmp_path / "artifacts",
        journal_root=tmp_path / "journal",
    )
    database = BenchDatabase(settings)
    database.create_schema()
    store = ArtifactStore(tmp_path / "artifacts")
    with database.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), now() - timedelta(days=1))
        inventory.add_board(unit, artix("bx-15"), now() - timedelta(days=1))
        digest = registry.ingest(unit, store, read_bitstream(bitfile()), now(), channel="qual")
    monkeypatch.setenv("FPGA_ARTIFACT_ROOT", str(tmp_path / "artifacts"))
    monkeypatch.setenv("FPGA_JOURNAL_ROOT", str(tmp_path / "journal"))
    return database, url, digest.digest


@pytest.fixture()
def run(workspace):
    _, url, _ = workspace
    runner = CliRunner()

    def invoke(*args):
        return runner.invoke(cli, ["--database", url, *args], catch_exceptions=False)

    return invoke


def queue_job(workspace, board="bx-14", mode="jtag", hold=True):
    database, _, digest = workspace
    with database.unit() as unit:
        if hold:
            leasing.acquire(unit, board, "team-a", "capture", now() - timedelta(minutes=1))
        return programming.submit(unit, board, digest, mode, "team-a", now())


def strand(workspace, board="bx-14", mode="jtag"):
    """A job the database calls running, as a crash would have left it."""
    database, _, _ = workspace
    job = queue_job(workspace, board=board, mode=mode)
    with database.unit() as unit:
        unit.jobs.save(unit.jobs.get(job.id).start(now()))
        inventory.move_state(unit, board, "programming", now(), actor="team-a")
    return job


def test_a_quiet_bench_has_nothing_to_do(run):
    assert "nothing to do" in run("worker", "run").output


def test_the_worker_runs_a_ready_job(run, workspace):
    job = queue_job(workspace)
    result = run("worker", "run")
    assert f"ran       {job.id} passed" in result.output


def test_the_worker_hands_out_a_board_before_running_jobs(run, workspace):
    run(
        "queue",
        "submit",
        "--holder",
        "team-b",
        "--purpose",
        "capture",
        "--needs",
        "device=xc7a100t",
    )
    body = json.loads(run("--json", "worker", "run").output)
    assert body["assigned"] == ["req-0001 -> bx-15"]


def test_dispatch_can_be_left_out(run, workspace):
    run(
        "queue",
        "submit",
        "--holder",
        "team-b",
        "--purpose",
        "capture",
        "--needs",
        "device=xc7a100t",
    )
    body = json.loads(run("--json", "worker", "run", "--no-dispatch").output)
    assert body["assigned"] == []


def test_the_worker_recovers_an_interrupted_load_first(run, workspace):
    job = strand(workspace)
    body = json.loads(run("--json", "worker", "run").output)
    assert f"requeued {job.id}" in body["recovered"][0]


def test_an_interrupted_flash_write_faults_the_board(run, workspace):
    strand(workspace, mode="flash-update")
    run("worker", "run")
    assert json.loads(run("--json", "boards", "show", "bx-14").output)["state"] == "faulted"


def test_recovery_can_be_skipped(run, workspace):
    strand(workspace)
    body = json.loads(run("--json", "worker", "run", "--no-recover").output)
    assert body["recovered"] == []


def test_the_recover_command_says_when_nothing_was_interrupted(run):
    assert "nothing was interrupted" in run("worker", "recover").output


def test_the_recover_command_lists_what_it_did(run, workspace):
    job = strand(workspace)
    body = json.loads(run("--json", "worker", "recover").output)
    assert {"what": "requeued", "subject": job.id} in body
    assert {"what": "returned", "subject": "bx-14"} in body


def test_two_rounds_run_two_jobs(run, workspace):
    queue_job(workspace)
    queue_job(workspace, hold=False)
    body = json.loads(run("--json", "worker", "run", "--rounds", "2").output)
    assert [entry["state"] for entry in body["ran"]] == ["passed", "passed"]


def test_a_round_with_nothing_ready_stops_early(run, workspace):
    queue_job(workspace)
    body = json.loads(run("--json", "worker", "run", "--rounds", "5").output)
    assert len(body["ran"]) == 1


def test_zero_rounds_is_a_usage_error(run):
    result = run("worker", "run", "--rounds", "0")
    assert result.exit_code != 0
    assert "at least one round" in result.output


def test_the_journal_records_what_the_worker_ran(run, workspace, tmp_path):
    queue_job(workspace)
    run("worker", "run")
    lines = (tmp_path / "journal" / "jobs.jsonl").read_text(encoding="utf-8")
    assert '"event": "passed"' in lines


def test_a_job_that_fails_its_gates_is_reported_not_hidden(run, workspace):
    queue_job(workspace, hold=False)
    body = json.loads(run("--json", "worker", "run").output)
    assert body["ran"][0]["state"] == "failed"
    assert "lease" in body["ran"][0]["detail"]
