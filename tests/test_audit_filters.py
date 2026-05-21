import json
from datetime import UTC, datetime, timedelta

import pytest
from click.testing import CliRunner
from fastapi.testclient import TestClient

from boardsetup import artix, kintex
from fpga.api.app import build_app
from fpga.api.deps import now as api_now
from fpga.audit import AuditEntry
from fpga.cli import audit as audit_commands  # noqa: F401  (registers the command)
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from fpga.services import audit as audit_service
from fpga.services import inventory


def now():
    return datetime.now(UTC)


def line(unit, at, actor="sam", action="lease", subject="bx-14", detail=""):
    return unit.audit.add(
        AuditEntry(at=at, actor=actor, action=action, subject=subject, detail=detail)
    )


@pytest.fixture()
def database(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    built = BenchDatabase(BenchSettings(database_url=url))
    built.create_schema()
    with built.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), now() - timedelta(days=2))
        inventory.add_board(unit, artix("bx-15"), now() - timedelta(days=2))
        line(unit, now() - timedelta(hours=30), actor="sam", action="lease", subject="bx-14")
        line(unit, now() - timedelta(hours=3), actor="nightly", action="program", subject="bx-14")
        line(unit, now() - timedelta(hours=1), actor="nightly", action="program", subject="bx-15")
    return built, url


@pytest.fixture()
def run(database):
    _, url = database
    runner = CliRunner()

    def invoke(*args):
        return runner.invoke(cli, ["--database", url, *args], catch_exceptions=False)

    return invoke


@pytest.fixture()
def client(database):
    built, _ = database
    app = build_app(built, BenchSettings())
    app.dependency_overrides[api_now] = now
    return TestClient(app)


def search(database, **rest):
    built, _ = database
    with built.unit() as unit:
        return audit_service.search(unit, **rest)


def test_the_whole_trail_comes_back_newest_first(database):
    found = search(database)
    assert [entry.subject for entry in found] == ["bx-15", "bx-14", "bx-14"]


def test_one_subject_can_be_picked_out(database):
    assert {entry.subject for entry in search(database, subject="bx-15")} == {"bx-15"}


def test_one_actor_can_be_picked_out(database):
    assert {entry.actor for entry in search(database, actor="nightly")} == {"nightly"}


def test_one_action_can_be_picked_out(database):
    assert len(search(database, action="lease")) == 1


def test_the_filters_combine(database):
    found = search(database, actor="nightly", subject="bx-14")
    assert len(found) == 1
    assert found[0].subject == "bx-14"


def test_a_date_leaves_out_what_is_older(database):
    found = search(database, since=now() - timedelta(hours=6))
    assert len(found) == 2


def test_the_limit_holds(database):
    assert len(search(database, limit=1)) == 1


def test_a_filter_matching_nothing_is_empty(database):
    assert search(database, actor="nobody") == ()


def test_the_command_prints_the_trail(run):
    body = json.loads(run("--json", "audit").output)
    assert [entry["subject"] for entry in body] == ["bx-15", "bx-14", "bx-14"]


def test_the_command_filters_by_actor(run):
    body = json.loads(run("--json", "audit", "--actor", "sam").output)
    assert [entry["actor"] for entry in body] == ["sam"]


def test_the_command_filters_by_hours(run):
    body = json.loads(run("--json", "audit", "--hours", "6").output)
    assert len(body) == 2


def test_the_command_refuses_a_silly_limit(run):
    result = run("audit", "--limit", "0")
    assert result.exit_code != 0
    assert "at least one line" in result.output


def test_the_route_still_answers_without_filters(client):
    body = client.get("/audit").json()
    assert len(body) == 3


def test_the_route_filters_by_actor(client):
    body = client.get("/audit?actor=nightly").json()
    assert {entry["actor"] for entry in body} == {"nightly"}


def test_the_route_filters_by_action_and_subject(client):
    body = client.get("/audit?action=program&subject=bx-15").json()
    assert len(body) == 1
    assert body[0]["subject"] == "bx-15"


def test_the_route_filters_by_date(client):
    since = (now() - timedelta(hours=6)).isoformat()
    response = client.get("/audit", params={"since": since})
    assert len(response.json()) == 2


def test_the_route_refuses_a_silly_limit(client):
    assert client.get("/audit?limit=0").status_code == 422
