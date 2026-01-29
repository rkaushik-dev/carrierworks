import json
from datetime import UTC, datetime, timedelta

import pytest
from click.testing import CliRunner

from boardsetup import artix, kintex
from fpga.cli import leases as lease_commands  # noqa: F401  (registers the commands)
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory


def now():
    return datetime.now(UTC)


@pytest.fixture()
def bench(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    database = BenchDatabase(BenchSettings(database_url=url))
    database.create_schema()
    with database.unit() as unit:
        # a day ago, so a test can put a lease in the recent past
        arrived = now() - timedelta(days=1)
        inventory.add_board(unit, kintex("bx-14"), arrived)
        inventory.add_board(unit, artix("bx-15"), arrived)
    return url


@pytest.fixture()
def run(bench):
    runner = CliRunner()

    def invoke(*args):
        return runner.invoke(cli, ["--database", bench, *args], catch_exceptions=False)

    return invoke


def take(run, *extra):
    return run(
        "lease",
        "take",
        "--holder",
        "team-a",
        "--purpose",
        "capture regression",
        *extra,
    )


def test_taking_a_named_board_says_until_when(run):
    result = take(run, "--board", "bx-14")
    assert result.exit_code == 0
    assert "bx-14 is yours until" in result.output
    assert "lease-00001" in result.output


def test_taking_a_board_marks_it_leased(run):
    take(run, "--board", "bx-14")
    body = json.loads(run("--json", "boards", "show", "bx-14").output)
    assert body["state"] == "leased"
    assert body["held_by"] == "team-a"


def test_a_board_somebody_else_has_is_refused_with_their_name(run):
    take(run, "--board", "bx-14")
    result = run(
        "lease",
        "take",
        "--board",
        "bx-14",
        "--holder",
        "team-b",
        "--purpose",
        "bring up",
    )
    assert result.exit_code != 0
    assert "already leased" in result.output
    assert "team-a" in result.output


def test_a_board_can_be_asked_for_by_what_it_needs(run):
    result = take(run, "--needs", "adc-14bit")
    assert result.exit_code == 0
    assert "bx-14 is yours" in result.output


def test_asking_for_the_smallest_thing_that_fits_gets_the_small_board(run):
    result = take(run, "--needs", "jtag")
    assert "bx-15 is yours" in result.output


def test_asking_for_something_nothing_has_says_why_each_board_failed(run):
    result = take(run, "--needs", "ddr4-sodimm")
    assert result.exit_code != 0
    assert "nothing free matches" in result.output
    assert "no ddr4-sodimm" in result.output


def test_asking_for_neither_a_board_nor_a_requirement_is_a_usage_error(run):
    result = take(run)
    assert result.exit_code != 0
    assert "say which board" in result.output


def test_the_lease_listing_shows_who_has_what(run):
    take(run, "--board", "bx-14")
    result = run("lease", "list")
    assert "LEASE" in result.output
    assert "team-a" in result.output
    assert "capture regression" in result.output


def test_the_listing_can_be_narrowed_to_one_holder(run):
    take(run, "--board", "bx-14")
    run(
        "lease",
        "take",
        "--board",
        "bx-15",
        "--holder",
        "team-b",
        "--purpose",
        "bring up",
    )
    body = json.loads(run("--json", "lease", "list", "--holder", "team-b").output)
    assert [entry["board"] for entry in body] == ["bx-15"]


def test_an_empty_bench_lists_nothing(run):
    assert "nothing to show" in run("lease", "list").output


def test_releasing_frees_the_board(run):
    take(run, "--board", "bx-14")
    result = run("lease", "release", "lease-00001", "--reason", "finished")
    assert "bx-14 is free again" in result.output
    assert json.loads(run("--json", "boards", "show", "bx-14").output)["state"] == "available"


def test_releasing_a_lease_nobody_issued_is_an_error(run):
    result = run("lease", "release", "lease-09999")
    assert result.exit_code != 0
    assert "no lease known as lease-09999" in result.output


def test_renewing_pushes_the_expiry_out(run):
    take(run, "--board", "bx-14", "--minutes", "30")
    before = datetime.fromisoformat(json.loads(run("--json", "lease", "list").output)[0]["until"])
    result = run("--json", "lease", "renew", "lease-00001", "--minutes", "120")
    after = datetime.fromisoformat(json.loads(result.output)["expires_at"])
    assert after > before


def test_a_kept_lease_is_not_preemptible(run, bench):
    take(run, "--board", "bx-14", "--keep")
    database = BenchDatabase(BenchSettings(database_url=bench))
    with database.unit() as unit:
        lease = unit.leases.get("lease-00001")
    assert not lease.preemptible


def test_the_sweep_says_when_there_was_nothing_to_do(run):
    assert "nothing had run out" in run("lease", "sweep").output


def test_the_sweep_closes_what_has_run_out(run, bench):
    from fpga.services import leasing

    database = BenchDatabase(BenchSettings(database_url=bench))
    with database.unit() as unit:
        leasing.acquire(unit, "bx-14", "team-a", "capture", now() - timedelta(hours=3), minutes=30)
    result = run("lease", "sweep")
    assert "bx-14" in result.output
    assert json.loads(run("--json", "boards", "show", "bx-14").output)["state"] == "available"
