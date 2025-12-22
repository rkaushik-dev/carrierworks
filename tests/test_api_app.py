import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from fpga.api.app import build_app, status_for
from fpga.config import BenchSettings
from fpga.errors import (
    BenchError,
    ChainError,
    ConfigurationError,
    Conflict,
    DeviceMismatch,
    NotFound,
    ValidationError,
)
from fpga.persistence.unit import BenchDatabase


@pytest.fixture()
def app():
    return build_app(BenchDatabase.in_memory(), BenchSettings(site="lab-b"))


@pytest.fixture()
def client(app):
    return TestClient(app)


def test_health_says_which_site_it_is(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["site"] == "lab-b"
    assert response.json()["status"] == "ok"


def test_the_settings_route_shows_what_is_in_force(client):
    body = client.get("/settings").json()
    assert body["default_lease_minutes"] == 60
    assert body["require_timing_closure"] is True


def test_nothing_secret_is_in_the_settings_route(client):
    body = client.get("/settings").json()
    assert "database_url" not in body
    assert "api_key_header" not in body


@pytest.mark.parametrize(
    ("error", "expected"),
    [
        (NotFound("board", "bx-99"), 404),
        (Conflict("that board is already leased"), 409),
        (ValidationError("bad asset tag"), 422),
        (DeviceMismatch("wrong device", expected="a", found="b"), 422),
        (ChainError("the chain did not answer"), 503),
        (ConfigurationError("two boards claim one slot"), 500),
        (BenchError("something else"), 400),
    ],
)
def test_every_error_has_its_status(error, expected):
    assert status_for(error) == expected


def test_an_error_comes_back_in_the_one_shape(app):
    @app.get("/boom")
    def boom():
        raise NotFound("board", "bx-99")

    response = TestClient(app).get("/boom")
    assert response.status_code == 404
    body = response.json()["error"]
    assert body["code"] == "not_found"
    assert body["details"] == {"kind": "board", "key": "bx-99"}
    assert body["retryable"] is False


def test_a_transient_error_says_it_can_be_retried(app):
    @app.get("/flaky")
    def flaky():
        raise ChainError("the chain read as all ones", board="bx-14")

    response = TestClient(app).get("/flaky")
    assert response.status_code == 503
    assert response.json()["error"]["retryable"] is True
    assert response.json()["error"]["details"]["board"] == "bx-14"


def test_the_app_carries_its_database_and_settings(app):
    assert isinstance(app, FastAPI)
    assert app.state.settings.site == "lab-b"
    with app.state.database.unit() as unit:
        assert unit.boards.all() == ()


def test_the_app_builds_with_defaults():
    app = build_app(BenchDatabase.in_memory())
    assert app.state.settings.site == "lab-a"
    assert TestClient(app).get("/health").status_code == 200


def test_a_route_that_does_not_exist_is_a_plain_404(client):
    assert client.get("/nothing-here").status_code == 404
