import json
from datetime import UTC, datetime, timedelta

import pytest
from click.testing import CliRunner

from boardsetup import artix, kintex
from fpga.cli import audit as audit_commands  # noqa: F401  (registers the command)
from fpga.cli import leases as lease_commands  # noqa: F401  (registers the commands)
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.errors import ValidationError
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory, leasing


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


def hold(database, board="bx-14", holder="team-a", minutes=60, ago=0):
    built, _ = database
    with built.unit() as unit:
        return leasing.acquire(
            unit,
            board,
            holder,
            "capture",
            now() - timedelta(minutes=ago),
            minutes=minutes,
        )


def soon(database, **rest):
    built, _ = database
    with built.unit() as unit:
        return leasing.expiring(unit, now(), **rest)


def test_a_lease_with_hours_left_is_not_expiring(database):
    hold(database, minutes=300)
    assert soon(database) == ()


def test_a_lease_about_to_run_out_is_listed(database):
    lease = hold(database, minutes=60, ago=50)
    assert [entry.id for entry in soon(database)] == [lease.id]


def test_the_window_can_be_widened(database):
    hold(database, minutes=60, ago=20)
    assert soon(database) == ()
    assert len(soon(database, within=timedelta(minutes=45))) == 1


def test_the_soonest_comes_first(database):
    later = hold(database, board="bx-14", minutes=60, ago=45)
    sooner = hold(database, board="bx-15", minutes=60, ago=55)
    found = soon(database, within=timedelta(minutes=20))
    assert [entry.id for entry in found] == [sooner.id, later.id]


def test_a_released_lease_is_not_expiring(database):
    lease = hold(database, minutes=60, ago=50)
    built, _ = database
    with built.unit() as unit:
        leasing.release(unit, lease.id, now(), "done")
    assert soon(database) == ()


def test_a_window_with_no_length_is_refused(database):
    with pytest.raises(ValidationError):
        soon(database, within=timedelta(0))


def test_a_reminder_writes_one_line_per_lease(database):
    lease = hold(database, minutes=60, ago=50)
    built, _ = database
    with built.unit() as unit:
        warned = leasing.remind(unit, now())
        trail = unit.audit.for_subject("bx-14")
    assert [entry.id for entry in warned] == [lease.id]
    assert [line.action for line in trail if line.actor == "bench"] == ["expiring"]


def test_the_reminder_says_who_and_how_long(database):
    hold(database, holder="team-b", minutes=60, ago=50)
    built, _ = database
    with built.unit() as unit:
        leasing.remind(unit, now())
        trail = unit.audit.for_subject("bx-14")
    detail = next(line.detail for line in trail if line.actor == "bench")
    assert "team-b" in detail
    assert "runs out in 9 minutes" in detail


def test_nothing_expiring_writes_nothing(database):
    hold(database, minutes=300)
    built, _ = database
    with built.unit() as unit:
        assert leasing.remind(unit, now()) == ()
        trail = unit.audit.for_subject("bx-14")
        assert [line for line in trail if line.actor == "bench"] == []


def test_the_command_lists_what_is_about_to_go(run, database):
    hold(database, minutes=60, ago=50)
    body = json.loads(run("--json", "lease", "expiring").output)
    assert body[0]["asset"] == "bx-14"
    assert body[0]["left"] == "9m"


def test_the_command_window_can_be_widened(run, database):
    hold(database, minutes=60, ago=20)
    assert json.loads(run("--json", "lease", "expiring").output) == []
    assert len(json.loads(run("--json", "lease", "expiring", "--minutes", "45").output)) == 1


def test_the_command_can_write_the_reminder(run, database):
    hold(database, minutes=60, ago=50)
    run("lease", "expiring", "--remind")
    body = json.loads(run("--json", "audit", "--action", "expiring").output)
    assert len(body) == 1


def test_the_command_is_quiet_when_nothing_is_expiring(run, database):
    hold(database, minutes=300)
    assert "nothing to show" in run("lease", "expiring").output
