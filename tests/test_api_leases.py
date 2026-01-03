from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from boardsetup import artix, kintex
from fpga.api.app import build_app
from fpga.api.deps import now
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory

WHEN = datetime(2026, 1, 12, 9, 0, tzinfo=UTC)


class Clock:
    def __init__(self, moment=WHEN):
        self.moment = moment

    def __call__(self):
        return self.moment

    def tick(self, **delta):
        self.moment = self.moment + timedelta(**delta)
        return self.moment


@pytest.fixture()
def database():
    live = BenchDatabase.in_memory()
    with live.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), WHEN)
        inventory.add_board(unit, artix("bx-15"), WHEN)
    return live


@pytest.fixture()
def clock():
    return Clock()


@pytest.fixture()
def client(database, clock):
    app = build_app(database, BenchSettings(max_lease_minutes=240))
    app.dependency_overrides[now] = clock
    return TestClient(app)


def take(client, tag="bx-14", **changes):
    payload = {"holder": "team-a", "purpose": "capture regression"}
    payload.update(changes)
    return client.post(f"/boards/{tag}/lease", json=payload)


def test_taking_a_board_gives_a_lease(client):
    response = take(client)
    assert response.status_code == 201
    body = response.json()
    assert body["id"] == "lease-00001"
    assert body["board"] == "bx-14"
    assert body["holder"] == "team-a"


def test_taking_a_board_marks_it_leased(client):
    take(client)
    assert client.get("/boards/bx-14").json()["state"] == "leased"


def test_a_board_cannot_be_taken_twice(client):
    take(client)
    response = take(client, holder="team-b")
    assert response.status_code == 409
    assert response.json()["error"]["details"]["holder"] == "team-a"


def test_a_board_out_of_service_cannot_be_taken(client):
    client.post("/boards/bx-14/state", json={"state": "faulted", "reason": "chain"})
    response = take(client)
    assert response.status_code == 409
    assert response.json()["error"]["details"]["state"] == "faulted"


def test_a_lease_longer_than_the_ceiling_is_refused(client):
    response = take(client, minutes=600)
    assert response.status_code == 422
    assert response.json()["error"]["details"]["ceiling"] == 240


def test_ids_carry_on_across_boards(client):
    assert take(client).json()["id"] == "lease-00001"
    assert take(client, tag="bx-15").json()["id"] == "lease-00002"


def test_the_live_leases_are_listed(client):
    take(client)
    take(client, tag="bx-15", holder="team-b")
    listed = client.get("/leases").json()
    assert {entry["board"] for entry in listed} == {"bx-14", "bx-15"}
    mine = client.get("/leases?holder=team-b").json()
    assert [entry["board"] for entry in mine] == ["bx-15"]


def test_a_board_says_what_holds_it(client):
    assert client.get("/boards/bx-14/lease").json() is None
    take(client)
    assert client.get("/boards/bx-14/lease").json()["holder"] == "team-a"


def test_a_board_nobody_has_is_a_404(client):
    assert client.get("/boards/bx-99/lease").status_code == 404


def test_releasing_a_lease_frees_the_board(client):
    lease = take(client).json()
    released = client.post(f"/leases/{lease['id']}/release?reason=finished").json()
    assert released["release_reason"] == "finished"
    assert client.get("/boards/bx-14").json()["state"] == "available"
    assert client.get("/boards/bx-14/lease").json() is None


def test_a_released_lease_cannot_be_released_again(client):
    lease = take(client).json()
    client.post(f"/leases/{lease['id']}/release")
    assert client.post(f"/leases/{lease['id']}/release").status_code == 422


def test_renewing_pushes_the_expiry_out(client, clock):
    lease = take(client, minutes=30).json()
    clock.tick(minutes=25)
    renewed = client.post(f"/leases/{lease['id']}/renew", json={"minutes": 60}).json()
    assert renewed["renewals"] == 1
    assert renewed["expires_at"] > lease["expires_at"]


def test_renewal_stops_at_the_ceiling(client, clock):
    lease = take(client, minutes=60).json()
    clock.tick(minutes=30)
    renewed = client.post(f"/leases/{lease['id']}/renew", json={"minutes": 600}).json()
    assert renewed["expires_at"].startswith("2026-01-12T13:00")


def test_a_lease_nobody_issued_is_a_404(client):
    assert client.get("/leases/lease-09999").status_code == 404
    assert client.post("/leases/lease-09999/renew", json={"minutes": 10}).status_code == 404


def test_the_sweep_closes_what_has_run_out(client, clock):
    take(client, minutes=30)
    clock.tick(minutes=31)
    swept = client.post("/leases/sweep").json()
    assert [entry["release_reason"] for entry in swept] == ["expired"]
    assert client.get("/boards/bx-14").json()["state"] == "available"
    assert client.post("/leases/sweep").json() == []


def test_a_board_swept_free_can_be_taken_again(client, clock):
    take(client, minutes=30)
    clock.tick(minutes=31)
    client.post("/leases/sweep")
    assert take(client, holder="team-b").status_code == 201
