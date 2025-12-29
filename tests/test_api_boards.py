from datetime import UTC, datetime

import pytest
from fastapi.testclient import TestClient

from boardsetup import artix, kintex
from fpga.api.app import build_app
from fpga.api.deps import now
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory

WHEN = datetime(2026, 1, 6, 9, 0, tzinfo=UTC)


@pytest.fixture()
def database():
    return BenchDatabase.in_memory()


@pytest.fixture()
def client(database):
    app = build_app(database, BenchSettings())
    app.dependency_overrides[now] = lambda: WHEN
    return TestClient(app)


def seed(database, *boards):
    with database.unit() as unit:
        for board in boards:
            inventory.add_board(unit, board, WHEN)


def board_payload(**changes):
    payload = {
        "asset": "bx-20",
        "model": "tx-carrier-a",
        "part": "XC7A100T-2FGG484I",
        "flash": "w25q64jv",
        "location": "rack-3/shelf-02/slot-1",
        "slots": [{"name": "fmc0", "connector": "fmc-lpc"}],
        "adapter_serial": "210308EF9999",
        "adapter_model": "jtag-hs3",
        "pdu": "pdu-c/outlet-04",
    }
    payload.update(changes)
    return payload


def test_an_empty_bench_lists_nothing(client):
    assert client.get("/boards").json() == []


def test_boards_come_back_with_their_state(client, database):
    seed(database, kintex("bx-14"))
    body = client.get("/boards").json()
    assert len(body) == 1
    assert body[0]["asset"] == "bx-14"
    assert body[0]["device"] == "xc7k325t"
    assert body[0]["state"] == "available"
    assert body[0]["cards"] == ["fmc-adc-4ch-14b"]


def test_one_board_comes_back_on_its_own(client, database):
    seed(database, kintex("bx-14"))
    body = client.get("/boards/BX-0014").json()
    assert body["location"] == "rack-2/shelf-04/slot-3"
    assert body["transceivers_free"] == 12


def test_a_board_nobody_has_is_a_404(client):
    response = client.get("/boards/bx-99")
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "not_found"


def test_a_board_can_be_added(client):
    response = client.post("/boards", json=board_payload())
    assert response.status_code == 201
    assert response.json()["asset"] == "bx-20"
    assert client.get("/boards/bx-20").json()["state"] == "available"


def test_a_board_that_collides_is_a_conflict_shaped_error(client, database):
    seed(database, kintex("bx-14", location="rack-3/shelf-02/slot-1"))
    response = client.post("/boards", json=board_payload())
    assert response.status_code == 409
    assert "already in that slot" in response.json()["error"]["message"]


def test_a_board_with_a_bad_part_is_refused(client):
    response = client.post("/boards", json=board_payload(part="XC7A100T"))
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "validation"


def test_the_available_filter_leaves_out_the_busy_ones(client, database):
    seed(database, kintex("bx-14"), artix("bx-15"))
    client.post("/boards/bx-14/state", json={"state": "leased"})
    listed = [board["asset"] for board in client.get("/boards?available=true").json()]
    assert listed == ["bx-15"]


def test_a_rack_filter_narrows_the_listing(client, database):
    seed(database, kintex("bx-14"), artix("bx-15"))
    listed = [board["asset"] for board in client.get("/boards?rack=rack-4").json()]
    assert listed == ["bx-15"]


def test_a_state_change_goes_through_the_state_machine(client, database):
    seed(database, kintex("bx-14"))
    body = client.post("/boards/bx-14/state", json={"state": "leased"}).json()
    assert body["state"] == "leased"
    refused = client.post("/boards/bx-14/state", json={"state": "quarantined", "reason": "no"})
    assert refused.status_code == 422


def test_faulting_a_board_records_the_reason(client, database):
    seed(database, kintex("bx-14"))
    body = client.post(
        "/boards/bx-14/state",
        json={"state": "faulted", "reason": "chain read as all ones", "actor": "nightly"},
    ).json()
    assert body["state"] == "faulted"
    assert body["state_reason"] == "chain read as all ones"


def test_a_card_can_be_fitted_and_removed(client, database):
    seed(database, kintex("bx-14"))
    fitted = client.put("/boards/bx-14/slots/fmc1?card=fmc-adc-2ch-16b").json()
    assert sorted(fitted["cards"]) == ["fmc-adc-2ch-16b", "fmc-adc-4ch-14b"]
    emptied = client.put("/boards/bx-14/slots/fmc1").json()
    assert emptied["cards"] == ["fmc-adc-4ch-14b"]


def test_a_card_that_does_not_fit_is_refused(client, database):
    seed(database, kintex("bx-14"))
    response = client.put("/boards/bx-14/slots/fmc1?card=fmc-sfp-quad")
    assert response.status_code == 500  # a configuration error: the bench is wrong
    assert "does not fit" in response.json()["error"]["message"]


def test_a_board_can_be_taken_off_the_bench(client, database):
    seed(database, kintex("bx-14"))
    assert client.delete("/boards/bx-14").status_code == 204
    assert client.get("/boards").json() == []


def test_removing_a_board_nobody_has_is_a_404(client):
    assert client.delete("/boards/bx-99").status_code == 404
