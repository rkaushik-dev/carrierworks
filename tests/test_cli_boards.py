import json
from datetime import UTC, datetime

import pytest
from click.testing import CliRunner

from boardsetup import artix, kintex
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory

WHEN = datetime(2026, 2, 13, 9, 0, tzinfo=UTC)


@pytest.fixture()
def bench(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    database = BenchDatabase(BenchSettings(database_url=url))
    database.create_schema()
    with database.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), WHEN)
        inventory.add_board(unit, artix("bx-15"), WHEN)
    return url


@pytest.fixture()
def run(bench):
    runner = CliRunner()

    def invoke(*args):
        return runner.invoke(cli, ["--database", bench, *args], catch_exceptions=False)

    return invoke


def test_the_version_command_says_which_site(run):
    result = run("version")
    assert result.exit_code == 0
    assert "fpga bench" in result.output
    assert "lab-a" in result.output


def test_boards_list_prints_columns(run):
    result = run("boards", "list")
    assert result.exit_code == 0
    assert "ASSET" in result.output
    assert "bx-14" in result.output
    assert "xc7k325t" in result.output


def test_boards_list_prints_json_when_asked(run):
    result = run("--json", "boards", "list")
    body = json.loads(result.output)
    assert {entry["asset"] for entry in body} == {"bx-14", "bx-15"}
    assert body[0]["state"] == "available"


def test_an_empty_bench_says_so(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'empty.db'}"
    BenchDatabase(BenchSettings(database_url=url)).create_schema()
    result = CliRunner().invoke(cli, ["--database", url, "boards", "list"])
    assert result.exit_code == 0
    assert "nothing to show" in result.output


def test_the_rack_filter_narrows_the_listing(run):
    result = run("--json", "boards", "list", "--rack", "rack-4")
    assert [entry["asset"] for entry in json.loads(result.output)] == ["bx-15"]


def test_the_available_filter_leaves_out_the_busy_ones(run):
    run("boards", "state", "bx-14", "leased")
    result = run("--json", "boards", "list", "--available")
    assert [entry["asset"] for entry in json.loads(result.output)] == ["bx-15"]


def test_showing_a_board_prints_what_is_on_it(run):
    result = run("boards", "show", "bx-14")
    assert result.exit_code == 0
    assert "rx-carrier-b" in result.output
    assert "held by  nobody" in result.output
    assert "jtag-hs2" in result.output


def test_showing_a_board_as_json_carries_the_detail(run):
    body = json.loads(run("--json", "boards", "show", "bx-14").output)
    assert body["device"] == "xc7k325t"
    assert body["cards"] == ["fmc-adc-4ch-14b"]
    assert body["transceivers_free"] == 12
    assert body["held_by"] is None


def test_a_board_nobody_has_is_an_error_with_a_readable_line(run):
    result = run("boards", "show", "bx-99")
    assert result.exit_code != 0
    assert "no board known as bx-99" in result.output


def test_a_state_move_prints_where_it_landed(run):
    result = run("boards", "state", "bx-14", "leased")
    assert result.exit_code == 0
    assert "bx-14 is now leased" in result.output
    assert json.loads(run("--json", "boards", "show", "bx-14").output)["state"] == "leased"


def test_a_move_the_state_machine_refuses_is_an_error(run):
    result = run("boards", "state", "bx-14", "quarantined", "--reason", "no")
    assert result.exit_code != 0
    assert "cannot go from available to quarantined" in result.output


def test_faulting_a_board_records_the_reason(run):
    run("boards", "state", "bx-14", "faulted", "--reason", "chain read as all ones")
    body = json.loads(run("--json", "boards", "show", "bx-14").output)
    assert body["state"] == "faulted"
    assert body["state_reason"] == "chain read as all ones"


def test_faulting_a_board_gives_its_lease_back(run, bench):
    """The command line reads the real clock, so the lease has to be live now."""
    from fpga.services import leasing

    database = BenchDatabase(BenchSettings(database_url=bench))
    with database.unit() as unit:
        lease = leasing.acquire(unit, "bx-14", "team-a", "capture", datetime.now(UTC))
    result = run("boards", "state", "bx-14", "faulted", "--reason", "smoke")
    assert lease.id in result.output
    assert "released" in result.output
