import json
from datetime import UTC, datetime, timedelta

import pytest
from click.testing import CliRunner

from boardsetup import artix, kintex
from fpga.cli import queue as queue_commands  # noqa: F401  (registers the commands)
from fpga.cli.main import cli
from fpga.config import BenchSettings
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


def ask(run, holder="team-a", needs="device=xc7k325t", **rest):
    args = ["queue", "submit", "--holder", holder, "--purpose", "capture run", "--needs", needs]
    for name, value in rest.items():
        args += [f"--{name.replace('_', '-')}", str(value)]
    return run(*args)


def test_a_request_takes_a_place_in_the_line(run):
    result = ask(run)
    assert result.exit_code == 0
    assert "req-0001 queued, 0 ahead of it" in result.output


def test_the_second_request_is_told_what_is_ahead(run):
    ask(run)
    assert "1 ahead of it" in ask(run, holder="team-b").output


def test_the_listing_shows_what_was_asked_for(run):
    ask(run, needs="card=fmc-adc-4ch-14b,transceivers>=4")
    body = json.loads(run("--json", "queue", "list").output)
    assert body[0]["needs"] == "card=fmc-adc-4ch-14b,transceivers>=4"
    assert body[0]["state"] == "waiting"


def test_a_request_with_no_requirements_says_anything(run):
    ask(run, needs="")
    body = json.loads(run("--json", "queue", "list").output)
    assert body[0]["needs"] == "anything"


def test_the_listing_is_in_the_order_it_will_be_served(run):
    ask(run, holder="team-a", priority=7)
    ask(run, holder="team-b", priority=2)
    body = json.loads(run("--json", "queue", "list").output)
    assert [entry["holder"] for entry in body] == ["team-b", "team-a"]


def test_the_listing_can_be_cut_to_one_team(run):
    ask(run, holder="team-a")
    ask(run, holder="team-b")
    body = json.loads(run("--json", "queue", "list", "--holder", "team-b").output)
    assert [entry["request"] for entry in body] == ["req-0002"]


def test_a_cancelled_request_leaves_the_line(run):
    ask(run)
    assert "req-0001 cancelled" in run("queue", "cancel", "req-0001").output
    assert json.loads(run("--json", "queue", "list").output) == []


def test_a_cancelled_request_is_still_there_under_all(run):
    ask(run)
    run("queue", "cancel", "req-0001")
    body = json.loads(run("--json", "queue", "list", "--all").output)
    assert body[0]["state"] == "cancelled"


def test_cancelling_a_request_nobody_made_is_an_error(run):
    assert run("queue", "cancel", "req-0009").exit_code != 0


def test_a_dispatch_hands_out_a_board(run):
    ask(run)
    result = run("queue", "dispatch")
    assert "req-0001 -> bx-14" in result.output


def test_a_dispatch_leaves_the_lease_behind(run):
    ask(run)
    run("queue", "dispatch")
    body = json.loads(run("--json", "boards", "show", "bx-14").output)
    assert body["held_by"] == "team-a"


def test_a_served_request_shows_the_board_it_got(run):
    ask(run)
    run("queue", "dispatch")
    body = json.loads(run("--json", "queue", "list", "--all").output)
    assert body[0]["state"] == "served"
    assert body[0]["board"] == "bx-14"


def test_a_request_for_a_part_nobody_has_waits(run):
    ask(run, needs="device=xcvu9p")
    body = json.loads(run("--json", "queue", "dispatch").output)
    assert body["assigned"] == []
    assert "nothing free matches" in body["waiting"]["req-0001"]


def test_an_empty_queue_dispatches_to_nothing(run):
    assert "nothing waiting" in run("queue", "dispatch").output


def test_a_dispatch_can_be_limited(run):
    ask(run, holder="team-a", needs="device=xc7k325t")
    ask(run, holder="team-b", needs="device=xc7a100t")
    body = json.loads(run("--json", "queue", "dispatch", "--limit", "1").output)
    assert len(body["assigned"]) == 1


def test_a_quota_holds_a_team_back(run, database):
    built, _ = database
    with built.unit() as unit:
        leasing.acquire(unit, "bx-15", "team-a", "earlier run", now() - timedelta(minutes=5))
    ask(run, holder="team-a")
    body = json.loads(run("--json", "queue", "dispatch", "--quota", "team-a=1").output)
    assert "quota is 1" in body["waiting"]["req-0001"]


def test_a_quota_that_is_not_a_number_is_a_usage_error(run):
    ask(run)
    result = run("queue", "dispatch", "--quota", "team-a=lots")
    assert result.exit_code != 0
    assert "team=count" in result.output


def test_two_teams_asking_for_different_parts_are_both_served(run):
    ask(run, holder="team-a", needs="device=xc7k325t")
    ask(run, holder="team-b", needs="device=xc7a100t")
    body = json.loads(run("--json", "queue", "dispatch").output)
    assert {entry["board"] for entry in body["assigned"]} == {"bx-14", "bx-15"}
