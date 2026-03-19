from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from boardsetup import artix, kintex
from fpga.api.app import build_app
from fpga.api.deps import now
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory

WHEN = datetime(2026, 3, 18, 11, 0, tzinfo=UTC)


@pytest.fixture()
def client():
    database = BenchDatabase.in_memory()
    with database.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), WHEN - timedelta(days=1))
        inventory.add_board(unit, artix("bx-15"), WHEN - timedelta(days=1))
    app = build_app(database, BenchSettings())
    app.dependency_overrides[now] = lambda: WHEN
    return TestClient(app)


def post(client, *readings, board="bx-14", start=None, every=20):
    """A controller posting a batch, oldest reading first."""
    first = start or WHEN - timedelta(seconds=every * (len(readings) - 1))
    return client.post(
        "/thermal/samples",
        json=[
            {
                "board": board,
                "millidegrees": value,
                "at": (first + timedelta(seconds=every * index)).isoformat(),
            }
            for index, value in enumerate(readings)
        ],
    )


def test_a_batch_of_readings_is_stored(client):
    response = post(client, 60_000, 61_000, 62_000)
    assert response.status_code == 201
    assert len(response.json()) == 3


def test_a_stored_reading_comes_back_in_celsius_too(client):
    body = post(client, 64_500).json()
    assert body[0]["celsius"] == 64.5
    assert body[0]["millidegrees"] == 64_500


def test_a_reading_with_no_time_is_stamped_by_the_server(client):
    response = client.post("/thermal/samples", json=[{"board": "bx-14", "millidegrees": 60_000}])
    assert response.json()[0]["at"].startswith("2026-03-18T11:00:00")


def test_a_batch_naming_a_board_nobody_has_is_a_404(client):
    response = client.post("/thermal/samples", json=[{"board": "bx-99", "millidegrees": 60_000}])
    assert response.status_code == 404


def test_one_bad_reading_takes_the_whole_batch_with_it(client):
    client.post(
        "/thermal/samples",
        json=[
            {"board": "bx-14", "millidegrees": 60_000},
            {"board": "bx-99", "millidegrees": 60_000},
        ],
    )
    assert client.get("/boards/bx-14/thermal").json() == []


def test_an_empty_batch_is_refused(client):
    assert client.post("/thermal/samples", json=[]).status_code == 422


def test_a_reading_off_the_scale_is_refused(client):
    response = client.post("/thermal/samples", json=[{"board": "bx-14", "millidegrees": 900_000}])
    assert response.status_code == 422


def test_status_covers_every_board_even_the_silent_ones(client):
    post(client, 60_000, 61_000)
    body = client.get("/thermal/status").json()
    assert {entry["board"] for entry in body} == {"bx-14", "bx-15"}
    states = {entry["board"]: entry["state"] for entry in body}
    assert states["bx-15"] == "unknown"


def test_the_worst_board_comes_first(client):
    post(client, 60_000, 61_000)
    post(client, 97_000, 98_000, board="bx-15")
    body = client.get("/thermal/status").json()
    assert body[0]["board"] == "bx-15"
    assert body[0]["alarming"] is True


def test_a_normal_board_is_not_alarming(client):
    post(client, 60_000, 61_000)
    entry = next(row for row in client.get("/thermal/status").json() if row["board"] == "bx-14")
    assert entry["state"] == "normal"
    assert entry["alarming"] is False


def test_the_window_can_be_narrowed(client):
    post(client, 60_000, 61_000, start=WHEN - timedelta(hours=3))
    body = client.get("/thermal/status?window=15").json()
    states = {entry["board"]: entry["state"] for entry in body}
    assert states["bx-14"] == "unknown"


def test_a_silly_window_is_refused(client):
    assert client.get("/thermal/status?window=0").status_code == 422


def test_one_board_history_comes_back_oldest_first(client):
    post(client, 60_000, 61_000, 62_000)
    body = client.get("/boards/bx-14/thermal").json()
    assert [entry["millidegrees"] for entry in body] == [60_000, 61_000, 62_000]


def test_history_for_a_board_nobody_has_is_a_404(client):
    assert client.get("/boards/bx-99/thermal").status_code == 404


def test_history_leaves_out_what_is_older_than_the_window(client):
    post(client, 55_000, start=WHEN - timedelta(hours=4))
    post(client, 60_000)
    body = client.get("/boards/bx-14/thermal?window=60").json()
    assert [entry["millidegrees"] for entry in body] == [60_000]


def test_a_tick_on_a_quiet_bench_does_nothing(client):
    post(client, 60_000, 61_000)
    post(client, 55_000, 56_000, board="bx-15")
    assert client.post("/thermal/tick").json() == []


def test_a_tick_shuts_a_hot_board_down(client):
    post(client, 101_000, 101_500)
    body = client.post("/thermal/tick").json()
    assert body[0]["board"] == "bx-14"
    assert body[0]["action"] == "shutdown"
    assert client.get("/boards/bx-14").json()["state"] == "faulted"


def test_a_tick_only_notes_a_warm_board(client):
    post(client, 87_000, 87_500)
    body = client.post("/thermal/tick").json()
    assert [entry["action"] for entry in body] == ["note"]
    assert client.get("/boards/bx-14").json()["state"] == "available"
