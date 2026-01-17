from datetime import UTC, datetime

import pytest
from fastapi.testclient import TestClient

from boardsetup import kintex
from fpga.api.app import build_app
from fpga.api.deps import now
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory, security

WHEN = datetime(2026, 1, 28, 9, 0, tzinfo=UTC)
HEADER = "x-bench-key"


@pytest.fixture()
def database():
    live = BenchDatabase.in_memory()
    with live.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), WHEN)
    return live


@pytest.fixture()
def client(database):
    app = build_app(database, BenchSettings())
    app.dependency_overrides[now] = lambda: WHEN
    return TestClient(app)


def issue(database, label, role, secret):
    with database.unit() as unit:
        security.issue(unit, label, role, WHEN, secret=secret)
    return {HEADER: secret}


def board_payload():
    return {
        "asset": "bx-30",
        "model": "tx-carrier-a",
        "part": "XC7A100T-2FGG484I",
        "flash": "w25q64jv",
        "location": "rack-3/shelf-05/slot-2",
    }


def test_a_bench_with_no_keys_is_open(client):
    assert client.get("/boards").status_code == 200
    assert client.post("/boards", json=board_payload()).status_code == 201


def test_the_first_key_closes_the_bench(client, database):
    issue(database, "dashboard", "reader", "reader-secret")
    assert client.post("/boards", json=board_payload()).status_code == 401


def test_a_reader_can_read(client, database):
    headers = issue(database, "dashboard", "reader", "reader-secret")
    assert client.get("/boards", headers=headers).status_code == 200


def test_a_reader_cannot_take_a_board(client, database):
    headers = issue(database, "dashboard", "reader", "reader-secret")
    response = client.post(
        "/boards/bx-14/lease",
        json={"holder": "team-a", "purpose": "capture"},
        headers=headers,
    )
    assert response.status_code == 403
    assert response.json()["error"]["details"] == {"needs": "operator", "holds": "reader"}


def test_an_operator_can_take_a_board(client, database):
    headers = issue(database, "nightly", "operator", "operator-secret")
    response = client.post(
        "/boards/bx-14/lease",
        json={"holder": "team-a", "purpose": "capture"},
        headers=headers,
    )
    assert response.status_code == 201


def test_an_operator_cannot_add_a_board(client, database):
    headers = issue(database, "nightly", "operator", "operator-secret")
    response = client.post("/boards", json=board_payload(), headers=headers)
    assert response.status_code == 403
    assert response.json()["error"]["details"]["needs"] == "admin"


def test_an_admin_can_do_both(client, database):
    headers = issue(database, "ops", "admin", "admin-secret")
    assert client.post("/boards", json=board_payload(), headers=headers).status_code == 201
    response = client.post(
        "/boards/bx-14/lease",
        json={"holder": "team-a", "purpose": "capture"},
        headers=headers,
    )
    assert response.status_code == 201


def test_a_key_nobody_issued_is_refused(client, database):
    issue(database, "ops", "admin", "admin-secret")
    response = client.get("/boards", headers={HEADER: "made-up"})
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "unauthorized"


def test_a_revoked_key_stops_working(client, database):
    headers = issue(database, "old laptop", "admin", "old-secret")
    listed = client.get("/keys", headers=headers).json()
    key_id = listed[0]["id"]
    client.post(f"/keys/{key_id}/revoke", headers=headers)
    assert client.get("/boards", headers=headers).status_code == 403


def test_a_key_can_be_issued_over_http(client, database):
    headers = issue(database, "ops", "admin", "admin-secret")
    response = client.post(
        "/keys",
        json={"label": "nightly runner", "role": "operator", "team": "signal-processing"},
        headers=headers,
    )
    assert response.status_code == 201
    body = response.json()
    assert body["role"] == "operator"
    assert body["secret"]
    fresh = {HEADER: body["secret"]}
    assert client.get("/keys/mine", headers=fresh).json()["label"] == "nightly runner"


def test_the_secret_only_comes_back_once(client, database):
    headers = issue(database, "ops", "admin", "admin-secret")
    client.post("/keys", json={"label": "runner", "role": "operator"}, headers=headers)
    listed = client.get("/keys", headers=headers).json()
    assert all("secret" not in entry for entry in listed)


def test_only_an_admin_can_list_keys(client, database):
    headers = issue(database, "nightly", "operator", "operator-secret")
    assert client.get("/keys", headers=headers).status_code == 403


def test_a_key_can_read_its_own_role(client, database):
    headers = issue(database, "nightly", "operator", "operator-secret")
    body = client.get("/keys/mine", headers=headers).json()
    assert body["role"] == "operator"
    assert body["label"] == "nightly"


def test_the_open_bench_reads_as_an_admin(client):
    body = client.get("/keys/mine").json()
    assert body["role"] == "admin"
    assert body["label"] == "open bench"
