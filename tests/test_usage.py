import json
from datetime import UTC, datetime, timedelta

import pytest
from click.testing import CliRunner

from boardsetup import artix, kintex
from fpga.cli import usage as usage_commands  # noqa: F401  (registers the commands)
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.errors import ValidationError
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory, leasing
from fpga.services import scheduling as queue_service
from fpga.services import usage as usage_service


def now():
    return datetime.now(UTC)


@pytest.fixture()
def database(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    built = BenchDatabase(BenchSettings(database_url=url))
    built.create_schema()
    with built.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), now() - timedelta(days=60))
        inventory.add_board(unit, artix("bx-15"), now() - timedelta(days=60))
    return built, url


@pytest.fixture()
def run(database):
    _, url = database
    runner = CliRunner()

    def invoke(*args):
        return runner.invoke(cli, ["--database", url, *args], catch_exceptions=False)

    return invoke


def held(database, board="bx-14", holder="team-a", hours_ago=48, minutes=120):
    """A lease that ran for a while and was given back."""
    built, _ = database
    taken = now() - timedelta(hours=hours_ago)
    with built.unit() as unit:
        lease = leasing.acquire(unit, board, holder, "capture", taken, minutes=minutes)
        leasing.release(unit, lease.id, taken + timedelta(minutes=minutes), "done")
    return lease


def look(database, **rest):
    built, _ = database
    with built.unit() as unit:
        return usage_service.usage(unit, now(), **rest)


def test_a_bench_nobody_used_is_all_idle(database):
    report = look(database)
    assert set(report.idle) == {"bx-14", "bx-15"}
    assert report.leases == 0
    assert report.busiest is None


def test_every_board_is_listed_even_the_unused_ones(database):
    held(database)
    assert set(look(database).utilisation) == {"bx-14", "bx-15"}


def test_a_two_hour_lease_over_a_week_is_about_one_percent(database):
    held(database)
    fraction = look(database).utilisation["bx-14"]
    assert 0.010 < fraction < 0.014


def test_the_hours_are_counted_against_the_team(database):
    held(database, minutes=180)
    assert look(database).holder_hours["team-a"] == 3.0


def test_two_teams_are_counted_apart(database):
    held(database, holder="team-a", minutes=60)
    held(database, board="bx-15", holder="team-b", minutes=120)
    hours = look(database).holder_hours
    assert hours == {"team-a": 1.0, "team-b": 2.0}


def test_a_lease_outside_the_window_does_not_count(database):
    held(database, hours_ago=24 * 40)
    report = look(database)
    assert report.leases == 0
    assert report.holder_hours == {}


def test_a_lease_that_straddles_the_edge_counts_only_its_overlap(database):
    held(database, hours_ago=24 * 7 + 1, minutes=120)
    report = look(database, window=timedelta(days=7))
    assert 0 < report.holder_hours["team-a"] < 1.1


def test_a_longer_window_pulls_in_older_leases(database):
    held(database, hours_ago=24 * 20)
    assert look(database, window=timedelta(days=30)).leases == 1


def test_the_busiest_board_is_the_one_held_longest(database):
    held(database, board="bx-14", minutes=60)
    held(database, board="bx-15", minutes=300)
    assert look(database).busiest == "bx-15"


def test_an_idle_board_is_named(database):
    held(database, board="bx-14")
    assert look(database).idle == ("bx-15",)


def test_a_window_with_no_length_is_refused(database):
    with pytest.raises(ValidationError):
        look(database, window=timedelta(0))


def test_the_waiting_requests_are_counted(database):
    built, _ = database
    with built.unit() as unit:
        queue_service.submit(unit, "team-b", "capture", "device=xcvu9p", now())
    assert look(database).waiting == 1


def test_the_report_reads_as_one_line(database):
    held(database)
    line = look(database).describe()
    assert "2 boards over 7.0 days, 1 leases" in line
    assert "idle: bx-15" in line


def test_the_board_listing_puts_the_busiest_first(run, database):
    held(database, board="bx-15", minutes=300)
    held(database, board="bx-14", minutes=60)
    body = json.loads(run("--json", "usage", "boards").output)
    assert [entry["asset"] for entry in body] == ["bx-15", "bx-14"]


def test_the_board_listing_shows_hours(run, database):
    held(database, minutes=120)
    body = json.loads(run("--json", "usage", "boards").output)
    assert body[0]["hours"] == 2.0


def test_the_team_listing_is_ordered_by_hours(run, database):
    held(database, holder="team-a", minutes=60)
    held(database, board="bx-15", holder="team-b", minutes=180)
    body = json.loads(run("--json", "usage", "teams").output)
    assert [entry["holder"] for entry in body] == ["team-b", "team-a"]


def test_the_idle_command_names_the_boards(run, database):
    held(database, board="bx-14")
    body = json.loads(run("--json", "usage", "idle").output)
    assert body["idle"] == ["bx-15"]


def test_the_idle_window_can_be_widened(run, database):
    held(database, board="bx-14", hours_ago=24 * 20)
    assert json.loads(run("--json", "usage", "idle", "--days", "7").output)["idle"] == [
        "bx-14",
        "bx-15",
    ]
    assert json.loads(run("--json", "usage", "idle", "--days", "30").output)["idle"] == ["bx-15"]


def test_the_summary_carries_the_numbers_behind_the_line(run, database):
    held(database)
    body = json.loads(run("--json", "usage", "summary").output)
    assert body["leases"] == 1
    assert body["busiest"] == "bx-14"
    assert body["idle"] == ["bx-15"]


def test_the_summary_prints_one_line_for_a_person(run, database):
    held(database)
    result = run("usage", "summary")
    assert result.exit_code == 0
    assert "busiest bx-14" in result.output
