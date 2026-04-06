import json
from datetime import UTC, datetime, timedelta

import pytest
from click.testing import CliRunner

from boardsetup import artix, kintex
from fpga.cli import upkeep as upkeep_commands  # noqa: F401  (registers the commands)
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.inventory.boards import BoardSlot
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory
from fpga.services import upkeep as upkeep_service


def now():
    return datetime.now(UTC)


def plain(asset):
    """A board with nothing fitted that measures anything."""
    return artix(
        asset,
        slots=(BoardSlot("pmod0", "pmod", "pmod-rs485"), BoardSlot("fmc0", "fmc-lpc")),
    )


@pytest.fixture()
def database(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    built = BenchDatabase(BenchSettings(database_url=url))
    built.create_schema()
    with built.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), now() - timedelta(days=400))
        inventory.add_board(unit, plain("bx-15"), now() - timedelta(days=400))
    return built, url


@pytest.fixture()
def run(database):
    _, url = database
    runner = CliRunner()

    def invoke(*args):
        return runner.invoke(cli, ["--database", url, *args], catch_exceptions=False)

    return invoke


def calibrate(database, board="bx-14", days_ago=5):
    built, _ = database
    with built.unit() as unit:
        upkeep_service.log_visit(
            unit,
            board,
            "calibration",
            now() - timedelta(days=days_ago),
            "sam",
        )


def test_a_visit_is_logged(run):
    result = run("upkeep", "log", "bx-14", "--by", "sam", "--notes", "reseated the fmc")
    assert result.exit_code == 0
    assert "reseated the fmc" in result.output


def test_a_kind_the_bench_does_not_know_is_refused(run):
    result = run("upkeep", "log", "bx-14", "--by", "sam", "--kind", "percussive")
    assert result.exit_code != 0
    assert "no such maintenance kind" in result.output


def test_a_visit_to_a_board_nobody_has_is_refused(run):
    assert run("upkeep", "log", "bx-99", "--by", "sam").exit_code != 0


def test_logging_alone_leaves_the_board_where_it_was(run):
    run("boards", "state", "bx-14", "maintenance", "--reason", "fan")
    run("upkeep", "log", "bx-14", "--by", "sam")
    assert json.loads(run("--json", "boards", "show", "bx-14").output)["state"] == "maintenance"


def test_done_puts_the_board_back(run):
    run("boards", "state", "bx-14", "maintenance", "--reason", "fan")
    result = run("upkeep", "done", "bx-14", "--by", "sam", "--notes", "new fan")
    assert "bx-14 is available" in result.output
    assert json.loads(run("--json", "boards", "show", "bx-14").output)["state"] == "available"


def test_done_records_the_faults_it_cleared(run):
    run("boards", "state", "bx-14", "faulted", "--reason", "chain went away")
    run("upkeep", "done", "bx-14", "--by", "sam", "--cleared", "2")
    body = json.loads(run("--json", "upkeep", "history", "bx-14").output)
    assert body[0]["cleared"] == 2


def test_the_history_of_one_board_is_listed(run):
    run("upkeep", "log", "bx-14", "--by", "sam", "--notes", "first")
    run("upkeep", "log", "bx-14", "--by", "kim", "--notes", "second")
    body = json.loads(run("--json", "upkeep", "history", "bx-14").output)
    assert [entry["notes"] for entry in body] == ["first", "second"]


def test_the_whole_bench_history_covers_every_board(run):
    run("upkeep", "log", "bx-14", "--by", "sam")
    run("upkeep", "log", "bx-15", "--by", "kim")
    body = json.loads(run("--json", "upkeep", "history").output)
    assert {entry["asset"] for entry in body} == {"bx-14", "bx-15"}


def test_history_for_a_board_nobody_has_is_an_error(run):
    assert run("upkeep", "history", "bx-99").exit_code != 0


def test_a_board_that_was_never_calibrated_says_so(run):
    body = json.loads(run("--json", "upkeep", "calibration", "bx-14").output)
    assert body["required"] is True
    assert body["never"] is True
    assert body["valid"] is False


def test_a_board_with_nothing_measuring_needs_none(run):
    body = json.loads(run("--json", "upkeep", "calibration", "bx-15").output)
    assert body["required"] is False
    assert body["valid"] is True


def test_a_fresh_calibration_holds(run, database):
    calibrate(database)
    body = json.loads(run("--json", "upkeep", "calibration", "bx-14").output)
    assert body["valid"] is True


def test_an_old_calibration_has_run_out(run, database):
    calibrate(database, days_ago=400)
    body = json.loads(run("--json", "upkeep", "calibration", "bx-14").output)
    assert body["valid"] is False


def test_the_due_list_names_what_has_run_out(run):
    body = json.loads(run("--json", "upkeep", "due").output)
    assert [entry["asset"] for entry in body] == ["bx-14"]
    assert body[0]["last"] == "never"


def test_a_calibrated_board_leaves_the_due_list(run, database):
    calibrate(database)
    assert json.loads(run("--json", "upkeep", "due").output) == []


def test_a_calibration_visit_can_be_the_thing_that_returns_the_board(run):
    run("boards", "state", "bx-14", "maintenance", "--reason", "due")
    run("upkeep", "done", "bx-14", "--by", "sam", "--kind", "calibration")
    assert json.loads(run("--json", "upkeep", "calibration", "bx-14").output)["valid"] is True
