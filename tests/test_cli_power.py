import json
from datetime import UTC, datetime, timedelta

import pytest
from click.testing import CliRunner

from boardsetup import artix, kintex
from fpga.cli import leases as lease_commands  # noqa: F401  (registers the commands)
from fpga.cli import power as power_commands
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.locations import PduChannel
from fpga.persistence.unit import BenchDatabase
from fpga.power.pdu import FakePdu
from fpga.services import inventory


def now():
    return datetime.now(UTC)


@pytest.fixture()
def url(tmp_path):
    address = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    database = BenchDatabase(BenchSettings(database_url=address))
    database.create_schema()
    with database.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), now() - timedelta(days=1))
        inventory.add_board(unit, artix("bx-15", outlet=None), now() - timedelta(days=1))
    return address


@pytest.fixture()
def run(url):
    runner = CliRunner()

    def invoke(*args):
        return runner.invoke(cli, ["--database", url, *args], catch_exceptions=False)

    return invoke


@pytest.fixture()
def rack():
    """A distribution unit registered the way a site would register one."""
    unit = FakePdu(outlets={"pdu-a/outlet-07": True}, currents={"pdu-a/outlet-07": 1450})
    power_commands.register_pdu("pdu-a", unit)
    yield unit
    power_commands._DRIVERS.clear()


def take(run, holder="team-a"):
    return run("lease", "take", "--board", "bx-14", "--holder", holder, "--purpose", "capture")


def test_the_tree_comes_out_in_dependency_order(run):
    body = json.loads(run("--json", "power", "tree", "bx-14").output)
    names = [entry["rail"] for entry in body]
    assert names[0] == "vccint"
    assert names.index("vccaux") > names.index("vccint")


def test_every_rail_says_what_it_waits_for(run):
    body = json.loads(run("--json", "power", "tree", "bx-14").output)
    after = {entry["rail"]: entry["after"] for entry in body}
    assert after["vccint"] == "-"
    assert "vccint" in after["vccaux"]


def test_the_up_plan_enables_settles_and_checks(run):
    body = json.loads(run("--json", "power", "plan", "bx-14").output)
    actions = [entry["action"] for entry in body]
    assert actions[0] == "enable"
    assert "check" in actions


def test_the_down_plan_reverses_the_order(run):
    up = json.loads(run("--json", "power", "plan", "bx-14").output)
    down = json.loads(run("--json", "power", "plan", "bx-14", "--down").output)
    first_up = next(entry["rail"] for entry in up if entry["action"] == "enable")
    last_down = [entry["rail"] for entry in down if entry["action"] == "disable"][-1]
    assert first_up == last_down


def test_a_plan_in_words_says_how_long_it_takes(run):
    result = run("power", "plan", "bx-14")
    assert "steps" in result.output
    assert "ms" in result.output


def test_a_board_in_maintenance_blocks_the_plan(run):
    run("boards", "state", "bx-14", "maintenance", "--reason", "recapping the fmc")
    result = run("power", "plan", "bx-14")
    assert "blocked" in result.output
    assert "maintenance" in result.output


def test_the_simulated_outlet_reads_as_on(run):
    body = json.loads(run("--json", "power", "show", "bx-14").output)
    assert body["on"] is True
    assert body["outlet"] == "pdu-a/outlet-07"
    assert body["blocked_by"] == []


def test_a_board_on_no_outlet_cannot_be_switched(run):
    result = run("power", "show", "bx-15")
    assert result.exit_code != 0
    assert "not on a switched outlet" in result.output


def test_switching_off_the_simulator_changes_nothing_real(run):
    body = json.loads(run("--json", "power", "off", "bx-14").output)
    assert body["changed"] is True
    assert json.loads(run("--json", "power", "show", "bx-14").output)["on"] is True


def test_a_live_switch_reaches_the_registered_unit(run, rack):
    body = json.loads(run("--json", "power", "off", "bx-14", "--live").output)
    assert body["changed"] is True
    assert rack.outlets["pdu-a/outlet-07"] is False
    assert rack.commands == ["off pdu-a/outlet-07"]


def test_switching_off_twice_is_quiet_the_second_time(run, rack):
    run("power", "off", "bx-14", "--live")
    result = run("power", "off", "bx-14", "--live")
    assert "was already off" in result.output


def test_a_live_switch_with_no_registered_unit_is_an_error(run):
    result = run("power", "off", "bx-14", "--live")
    assert result.exit_code != 0
    assert "no driver" in result.output


def test_somebody_elses_lease_blocks_the_switch(run, rack):
    take(run, holder="team-a")
    result = run("power", "cycle", "bx-14", "--live", "--holder", "team-b")
    assert result.exit_code != 0
    assert "interlocked" in result.output
    assert rack.commands == []


def test_the_holder_of_the_lease_can_cycle_it(run, rack):
    take(run, holder="team-a")
    body = json.loads(
        run("--json", "power", "cycle", "bx-14", "--live", "--holder", "team-a").output
    )
    assert body["off_ms"] == 250
    assert rack.commands == ["off pdu-a/outlet-07", "on pdu-a/outlet-07"]


def test_a_cycle_can_be_forced_past_a_lease(run, rack):
    take(run, holder="team-a")
    body = json.loads(
        run("--json", "power", "cycle", "bx-14", "--live", "--holder", "team-b", "--force").output
    )
    assert body["blocked_by"] != []
    assert rack.commands == ["off pdu-a/outlet-07", "on pdu-a/outlet-07"]


def test_a_short_off_time_is_stretched_to_the_floor(run, rack):
    body = json.loads(run("--json", "power", "cycle", "bx-14", "--live", "--off-ms", "10").output)
    assert body["off_ms"] == 250


def test_a_longer_off_time_is_kept(run, rack):
    body = json.loads(run("--json", "power", "cycle", "bx-14", "--live", "--off-ms", "900").output)
    assert body["off_ms"] == 900


def test_an_unreachable_unit_is_a_power_fault(run, rack):
    rack.unreachable.add("pdu-a")
    result = run("power", "show", "bx-14", "--live")
    assert result.exit_code != 0
    assert "did not answer" in result.output


def test_the_outlet_reports_what_it_is_drawing(run, rack):
    body = json.loads(run("--json", "power", "show", "bx-14", "--live").output)
    assert body["current_ma"] == 1450
    assert body["pdu"] == "fake-pdu"


def test_an_outlet_that_is_off_draws_nothing(run, rack):
    rack.switch(PduChannel("pdu-a", 7), False)
    body = json.loads(run("--json", "power", "show", "bx-14", "--live").output)
    assert body["on"] is False
    assert body["current_ma"] == 0
