import json
from datetime import UTC, datetime, timedelta

import pytest
from click.testing import CliRunner

from boardsetup import artix, kintex
from fpga.cli import leases as lease_commands  # noqa: F401  (registers the commands)
from fpga.cli import thermal as thermal_commands  # noqa: F401  (registers the commands)
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory
from fpga.services import thermal as thermal_service


def now():
    return datetime.now(UTC)


@pytest.fixture()
def database(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    built = BenchDatabase(BenchSettings(database_url=url))
    built.create_schema()
    with built.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), now() - timedelta(days=1))
        inventory.add_board(unit, artix("bx-15"), now() - timedelta(days=1))
    return built, url


@pytest.fixture()
def run(database):
    _, url = database
    runner = CliRunner()

    def invoke(*args):
        return runner.invoke(cli, ["--database", url, *args], catch_exceptions=False)

    return invoke


def feed(database, board, *readings, every=timedelta(seconds=20)):
    """Readings ending a moment ago, so the window they land in is the live one."""
    built, _ = database
    last = now() - timedelta(seconds=5)
    with built.unit() as unit:
        for index, value in enumerate(reversed(readings)):
            thermal_service.record(unit, board, last - every * index, value)


def test_a_reading_goes_in_from_the_command_line(run):
    result = run("thermal", "record", "bx-14", "64.5C")
    assert result.exit_code == 0
    assert "64.5C" in result.output


def test_a_reading_can_be_given_in_plain_degrees(run):
    body = json.loads(run("--json", "thermal", "record", "bx-14", "71").output)
    assert body["millidegrees"] == 71_000


def test_a_reading_off_another_sensor_keeps_its_source(run):
    body = json.loads(
        run("--json", "thermal", "record", "bx-14", "24C", "--source", "inlet").output
    )
    assert body["source"] == "inlet"


def test_a_reading_for_a_board_nobody_has_is_an_error(run):
    result = run("thermal", "record", "bx-99", "64C")
    assert result.exit_code != 0
    assert "bx-99" in result.output


def test_a_reading_that_is_not_a_temperature_is_an_error(run):
    result = run("thermal", "record", "bx-14", "warm")
    assert result.exit_code != 0


def test_a_bench_with_no_readings_is_all_unknown(run):
    body = json.loads(run("--json", "thermal", "status").output)
    assert {entry["state"] for entry in body} == {"unknown"}


def test_a_cool_board_reads_as_normal(run, database):
    feed(database, "bx-14", 60_000, 61_000, 62_000)
    body = json.loads(run("--json", "thermal", "status").output)
    states = {entry["asset"]: entry["state"] for entry in body}
    assert states["bx-14"] == "normal"


def test_the_worst_board_is_listed_first(run, database):
    feed(database, "bx-14", 60_000, 61_000)
    feed(database, "bx-15", 88_000, 89_000)
    body = json.loads(run("--json", "thermal", "status").output)
    assert body[0]["asset"] == "bx-15"


def test_the_listing_can_be_cut_to_what_is_alarming(run, database):
    feed(database, "bx-14", 60_000, 61_000)
    feed(database, "bx-15", 97_000, 98_000)
    body = json.loads(run("--json", "thermal", "status", "--alarming").output)
    assert [entry["asset"] for entry in body] == ["bx-15"]


def test_status_reports_the_window_average_rather_than_one_spike(run, database):
    feed(database, "bx-14", 60_000, 66_500)
    body = json.loads(run("--json", "thermal", "status").output)
    readings = {entry["asset"]: entry["reading"] for entry in body}
    assert readings["bx-14"] == "63.2C"


def test_history_comes_back_oldest_first(run, database):
    feed(database, "bx-14", 60_000, 61_000, 62_000)
    body = json.loads(run("--json", "thermal", "history", "bx-14").output)
    assert [entry["reading"] for entry in body] == ["60.0C", "61.0C", "62.0C"]


def test_history_for_a_board_nobody_has_is_an_error(run):
    assert run("thermal", "history", "bx-99").exit_code != 0


def test_a_quiet_bench_has_nothing_for_the_supervisor_to_do(run, database):
    feed(database, "bx-14", 60_000, 61_000)
    feed(database, "bx-15", 55_000, 56_000)
    assert "nothing to do" in run("thermal", "tick").output


def test_a_hot_board_is_shut_down_by_the_tick(run, database):
    feed(database, "bx-14", 101_000, 101_500)
    body = json.loads(run("--json", "thermal", "tick").output)
    assert body[0]["asset"] == "bx-14"
    assert body[0]["action"] == "shutdown"


def test_the_shutdown_takes_the_board_out_of_service(run, database):
    feed(database, "bx-14", 101_000, 101_500)
    run("thermal", "tick")
    assert json.loads(run("--json", "boards", "show", "bx-14").output)["state"] == "faulted"


def test_a_warm_board_is_only_noted(run, database):
    feed(database, "bx-14", 87_000, 87_500)
    feed(database, "bx-15", 55_000, 56_000)
    run("thermal", "tick")
    assert json.loads(run("--json", "boards", "show", "bx-14").output)["state"] == "available"


def test_pruning_says_how_much_it_dropped(run, database):
    built, _ = database
    with built.unit() as unit:
        thermal_service.record(unit, "bx-14", now() - timedelta(days=30), 60_000)
    body = json.loads(run("--json", "thermal", "prune").output)
    assert body["dropped"] == 1


def test_pruning_leaves_the_live_window_alone(run, database):
    feed(database, "bx-14", 60_000, 61_000)
    body = json.loads(run("--json", "thermal", "prune").output)
    assert body["dropped"] == 0
    assert len(json.loads(run("--json", "thermal", "history", "bx-14").output)) == 2
