from datetime import UTC, datetime, timedelta

import pytest
from fastapi import APIRouter
from fastapi.testclient import TestClient

from boardsetup import kintex
from fpga.api.app import build_app, retry_after_for, status_for
from fpga.api.deps import now
from fpga.config import BenchSettings
from fpga.errors import (
    BenchError,
    ChainError,
    ConfigurationError,
    Conflict,
    DeviceMismatch,
    Forbidden,
    NotFound,
    PowerFault,
    TransientBenchError,
    Unauthorized,
    ValidationError,
)
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory, leasing

WHEN = datetime(2026, 6, 16, 8, 0, tzinfo=UTC)

RAISERS: dict[str, BenchError] = {
    "transient": TransientBenchError("the adapter stopped answering"),
    "chain": ChainError("the chain read as all ones"),
    "power": PowerFault("the outlet did not switch"),
    "conflict": Conflict("that board is busy"),
    "not_found": NotFound("board", "bx-99"),
    "validation": ValidationError("that is not a temperature", field="millidegrees"),
    "unauthorized": Unauthorized("no key"),
    "forbidden": Forbidden("reader cannot do that"),
    "configuration": ConfigurationError("no such setting", setting="nope"),
    "mismatch": DeviceMismatch(
        "that image is for another part",
        expected="xc7k325t",
        found="xc7a100t",
    ),
}


@pytest.fixture()
def client():
    database = BenchDatabase.in_memory()
    with database.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), WHEN - timedelta(days=1))
    app = build_app(database, BenchSettings())
    app.dependency_overrides[now] = lambda: WHEN

    router = APIRouter()

    @router.get("/boom/{kind}")
    def boom(kind: str) -> dict[str, str]:
        raise RAISERS[kind]

    app.include_router(router)
    return TestClient(app)


def test_a_transient_failure_says_when_to_come_back(client):
    response = client.get("/boom/transient")
    assert response.status_code == 503
    assert response.headers["retry-after"] == "10"


def test_a_chain_failure_says_come_back_soon(client):
    assert client.get("/boom/chain").headers["retry-after"] == "5"


def test_a_power_failure_says_come_back_later(client):
    assert client.get("/boom/power").headers["retry-after"] == "30"


def test_a_conflict_does_not_say_to_retry(client):
    response = client.get("/boom/conflict")
    assert response.status_code == 409
    assert "retry-after" not in response.headers


def test_a_validation_error_does_not_say_to_retry(client):
    response = client.get("/boom/validation")
    assert response.status_code == 422
    assert "retry-after" not in response.headers


def test_every_error_carries_its_code_and_message(client):
    body = client.get("/boom/not_found").json()["error"]
    assert body["code"] == "not_found"
    assert body["retryable"] is False
    assert "bx-99" in str(body["details"])


def test_a_transient_error_says_it_is_retryable_in_the_body(client):
    body = client.get("/boom/transient").json()["error"]
    assert body["retryable"] is True
    assert body["code"] == "transient"


def test_the_statuses_are_what_the_clients_expect(client):
    expected = {
        "transient": 503,
        "chain": 503,
        "power": 503,
        "conflict": 409,
        "not_found": 404,
        "validation": 422,
        "unauthorized": 401,
        "forbidden": 403,
        "configuration": 500,
        "mismatch": 422,
    }
    for kind, code in expected.items():
        assert client.get(f"/boom/{kind}").status_code == code, kind


def test_the_helper_agrees_with_the_handler():
    for error in RAISERS.values():
        wait = retry_after_for(error)
        assert (wait is None) is not error.retryable
        assert status_for(error) >= 400


def test_a_real_conflict_from_a_route_carries_no_retry_after(client):
    client.post("/boards/bx-14/lease", json={"holder": "team-a", "purpose": "capture"})
    second = client.post("/boards/bx-14/lease", json={"holder": "team-b", "purpose": "capture"})
    assert second.status_code == 409
    assert "retry-after" not in second.headers


def test_a_real_not_found_from_a_route_carries_the_tag(client):
    body = client.get("/boards/bx-99").json()["error"]
    assert body["code"] == "not_found"
    assert "bx-99" in str(body["details"])


def test_a_route_that_worked_has_no_error_shape(
    client,
):
    body = client.get("/boards/bx-14").json()
    assert "error" not in body
    assert body["asset"] == "bx-14"


def test_the_lease_route_still_answers_after_an_error(client):
    client.get("/boom/transient")
    with BenchDatabase.in_memory().unit() as unit:
        inventory.add_board(unit, kintex("bx-20"), WHEN - timedelta(days=1))
        leasing.acquire(unit, "bx-20", "team-a", "capture", WHEN)
    assert client.get("/boards").status_code == 200
