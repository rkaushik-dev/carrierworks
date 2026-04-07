import json
from datetime import UTC, datetime, timedelta

import pytest
from click.testing import CliRunner

from boardsetup import artix, kintex
from fpga.cli import status as status_command  # noqa: F401  (registers the command)
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.inventory.boards import BoardSlot
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory, leasing, upkeep
from fpga.services import overview as overview_service
from fpga.services import scheduling as queue_service
from fpga.services import thermal as thermal_service


def now():
    return datetime.now(UTC)


def plain(asset, **rest):
    return artix(
        asset,
        slots=(BoardSlot("pmod0", "pmod", "pmod-rs485"), BoardSlot("fmc0", "fmc-lpc")),
        **rest,
    )


@pytest.fixture()
def database(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    built = BenchDatabase(BenchSettings(database_url=url, site="lab-a"))
    built.create_schema()
    with built.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), now() - timedelta(days=2))
        inventory.add_board(unit, plain("bx-15"), now() - timedelta(days=2))
        upkeep.log_visit(unit, "bx-14", "calibration", now() - timedelta(days=1), "sam")
    return built, url


@pytest.fixture()
def run(database):
    _, url = database
    runner = CliRunner()

    def invoke(*args):
        return runner.invoke(cli, ["--database", url, *args], catch_exceptions=False)

    return invoke


def look(database, **rest):
    built, _ = database
    with built.unit() as unit:
        return overview_service.overview(unit, now(), **rest)


def test_an_idle_bench_is_all_available(database):
    state = look(database)
    assert state.boards == {"available": 2}
    assert state.available == 2
    assert state.total_boards == 2


def test_a_lease_shows_up_as_held(database):
    built, _ = database
    with built.unit() as unit:
        leasing.acquire(unit, "bx-14", "team-a", "capture", now(), minutes=60)
    state = look(database)
    assert state.leases_held == 1
    assert state.boards["leased"] == 1


def test_a_lease_about_to_run_out_is_counted_separately(database):
    built, _ = database
    with built.unit() as unit:
        leasing.acquire(unit, "bx-14", "team-a", "capture", now() - timedelta(minutes=55))
    state = look(database)
    assert state.leases_expiring == 1


def test_a_lease_with_hours_left_is_not_expiring(database):
    built, _ = database
    with built.unit() as unit:
        leasing.acquire(unit, "bx-14", "team-a", "capture", now(), minutes=300)
    assert look(database).leases_expiring == 0


def test_a_waiting_request_is_counted(database):
    built, _ = database
    with built.unit() as unit:
        queue_service.submit(unit, "team-b", "capture", "device=xcvu9p", now() - timedelta(hours=1))
    state = look(database)
    assert state.queue_waiting == 1
    assert 3500 < state.queue_longest_wait_seconds < 3700


def test_an_empty_queue_has_no_longest_wait(database):
    assert look(database).queue_longest_wait_seconds == 0


def test_thermal_states_are_tallied(database):
    built, _ = database
    with built.unit() as unit:
        thermal_service.record(unit, "bx-14", now() - timedelta(seconds=20), 60_000)
        thermal_service.record(unit, "bx-14", now() - timedelta(seconds=5), 61_000)
    state = look(database)
    assert state.thermal["normal"] == 1
    assert state.thermal["unknown"] == 1


def test_a_bench_nobody_has_calibrated_is_not_healthy(database):
    built, _ = database
    with built.unit() as unit:
        inventory.add_board(
            unit,
            kintex("bx-16", location="rack-2/shelf-06/slot-2", serial="210308AA0001", outlet=12),
            now() - timedelta(days=1),
        )
    state = look(database)
    assert state.calibration_overdue == ("bx-16",)
    assert state.healthy is False


def test_a_quiet_calibrated_bench_is_healthy(database):
    built, _ = database
    with built.unit() as unit:
        thermal_service.record(unit, "bx-14", now() - timedelta(seconds=10), 60_000)
        thermal_service.record(unit, "bx-15", now() - timedelta(seconds=10), 55_000)
    assert look(database).healthy is True


def test_a_faulted_board_makes_the_bench_unhealthy(database):
    built, _ = database
    with built.unit() as unit:
        inventory.fault_board(unit, "bx-14", now(), "chain went away", actor="runner")
        thermal_service.record(unit, "bx-14", now() - timedelta(seconds=10), 60_000)
        thermal_service.record(unit, "bx-15", now() - timedelta(seconds=10), 55_000)
    assert look(database).healthy is False


def test_the_line_reads_as_one_sentence(database):
    assert "lab-a: 2 of 2 free" in look(database).describe()


def test_the_command_prints_the_roll_up(run):
    result = run("status")
    assert result.exit_code == 0
    assert "boards   available 2" in result.output
    assert "thermal  unknown 2" in result.output


def test_the_command_gives_the_same_numbers_as_json(run):
    body = json.loads(run("--json", "status").output)
    assert body["available"] == 2
    assert body["queue_waiting"] == 0
    assert body["calibration_overdue"] == []


def test_the_command_can_fail_when_something_needs_a_person(run, database):
    built, _ = database
    with built.unit() as unit:
        inventory.fault_board(unit, "bx-14", now(), "chain went away", actor="runner")
    assert run("status", "--fail-on-trouble").exit_code == 2


def test_the_command_is_quiet_when_the_bench_is_fine(run, database):
    built, _ = database
    with built.unit() as unit:
        thermal_service.record(unit, "bx-14", now() - timedelta(seconds=10), 60_000)
        thermal_service.record(unit, "bx-15", now() - timedelta(seconds=10), 55_000)
    assert run("status", "--fail-on-trouble").exit_code == 0
