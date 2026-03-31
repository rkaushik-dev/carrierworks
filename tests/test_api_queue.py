from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from boardsetup import artix, kintex
from fpga.api.app import build_app
from fpga.api.deps import now
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory, leasing

WHEN = datetime(2026, 4, 2, 10, 0, tzinfo=UTC)


@pytest.fixture()
def database():
    built = BenchDatabase.in_memory()
    with built.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), WHEN - timedelta(days=1))
        inventory.add_board(unit, artix("bx-15"), WHEN - timedelta(days=1))
    return built


@pytest.fixture()
def client(database):
    app = build_app(database, BenchSettings())
    app.dependency_overrides[now] = lambda: WHEN
    return TestClient(app)


def ask(client, holder="team-a", needs="device=xc7k325t", **rest):
    body = {"holder": holder, "purpose": "capture run", "needs": needs}
    body.update(rest)
    return client.post("/requests", json=body)


def test_a_request_goes_on_the_line(client):
    response = ask(client)
    assert response.status_code == 201
    body = response.json()
    assert body["id"] == "req-0001"
    assert body["state"] == "waiting"
    assert body["place"] == 1


def test_a_request_keeps_the_words_it_was_written_in(client):
    body = ask(client, needs="card=fmc-adc-4ch-14b,transceivers>=4").json()
    assert body["needs"] == ["card=fmc-adc-4ch-14b", "transceivers>=4"]


def test_a_priority_outside_the_range_is_refused(client):
    assert ask(client, priority=0).status_code == 422


def test_a_request_with_no_purpose_is_refused(client):
    assert client.post("/requests", json={"holder": "team-a", "purpose": " "}).status_code == 422


def test_the_line_comes_back_in_serving_order(client):
    ask(client, holder="team-a", priority=7)
    ask(client, holder="team-b", priority=2)
    body = client.get("/requests").json()
    assert [entry["holder"] for entry in body] == ["team-b", "team-a"]


def test_the_line_can_be_cut_to_one_team(client):
    ask(client, holder="team-a")
    ask(client, holder="team-b")
    body = client.get("/requests?holder=team-b").json()
    assert [entry["id"] for entry in body] == ["req-0002"]


def test_one_request_reads_back_with_its_place(client):
    ask(client, holder="team-a")
    ask(client, holder="team-b")
    body = client.get("/requests/req-0002").json()
    assert body["place"] == 2
    assert body["waited_seconds"] == 0


def test_a_request_nobody_made_is_a_404(client):
    assert client.get("/requests/req-0099").status_code == 404


def test_a_cancelled_request_leaves_the_line(client):
    ask(client)
    assert client.delete("/requests/req-0001").json()["state"] == "cancelled"
    assert client.get("/requests").json() == []


def test_a_cancelled_request_is_still_there_under_closed(client):
    ask(client)
    client.delete("/requests/req-0001")
    body = client.get("/requests?closed=true").json()
    assert [entry["state"] for entry in body] == ["cancelled"]


def test_cancelling_twice_is_a_422(client):
    ask(client)
    client.delete("/requests/req-0001")
    assert client.delete("/requests/req-0001").status_code == 422


def test_a_dispatch_hands_out_a_board(client):
    ask(client)
    body = client.post("/requests/dispatch", json={}).json()
    assert body["assigned"] == [{"request": "req-0001", "board": "bx-14", "lease": "lease-00001"}]


def test_a_served_request_shows_its_board(client):
    ask(client)
    client.post("/requests/dispatch", json={})
    body = client.get("/requests/req-0001").json()
    assert body["state"] == "served"
    assert body["board"] == "bx-14"
    assert body["place"] is None


def test_a_dispatch_that_finds_nothing_says_why(client):
    ask(client, needs="device=xcvu9p")
    body = client.post("/requests/dispatch", json={}).json()
    assert body["assigned"] == []
    assert "nothing free matches" in body["waiting"]["req-0001"]


def test_a_dispatch_can_be_limited(client):
    ask(client, holder="team-a", needs="device=xc7k325t")
    ask(client, holder="team-b", needs="device=xc7a100t")
    body = client.post("/requests/dispatch?limit=1", json={}).json()
    assert len(body["assigned"]) == 1


def test_a_quota_holds_a_team_back(client, database):
    with database.unit() as unit:
        leasing.acquire(unit, "bx-15", "team-a", "earlier", WHEN - timedelta(minutes=5))
    ask(client, holder="team-a")
    body = client.post("/requests/dispatch", json={"team-a": 1}).json()
    assert "quota is 1" in body["waiting"]["req-0001"]


def test_a_dispatch_takes_a_lease_the_board_route_can_see(client):
    ask(client)
    client.post("/requests/dispatch", json={})
    assert client.get("/boards/bx-14/lease").json()["holder"] == "team-a"
