from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from boardsetup import artix, kintex
from fpga.api.app import build_app
from fpga.api.deps import now
from fpga.config import BenchSettings
from fpga.inventory.boards import BoardSlot
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory, leasing, upkeep
from fpga.services import scheduling as queue_service
from fpga.services import thermal as thermal_service

WHEN = datetime(2026, 4, 15, 13, 0, tzinfo=UTC)


def plain(asset):
    return artix(
        asset,
        slots=(BoardSlot("pmod0", "pmod", "pmod-rs485"), BoardSlot("fmc0", "fmc-lpc")),
    )


@pytest.fixture()
def database():
    built = BenchDatabase.in_memory()
    with built.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), WHEN - timedelta(days=2))
        inventory.add_board(unit, plain("bx-15"), WHEN - timedelta(days=2))
        upkeep.log_visit(unit, "bx-14", "calibration", WHEN - timedelta(days=1), "sam")
    return built


@pytest.fixture()
def client(database):
    app = build_app(database, BenchSettings(site="lab-a"))
    app.dependency_overrides[now] = lambda: WHEN
    return TestClient(app)


def test_an_idle_bench_reads_as_all_available(client):
    body = client.get("/status").json()
    assert body["boards"] == {"available": 2}
    assert body["available"] == 2
    assert body["total_boards"] == 2


def test_the_roll_up_carries_its_own_sentence(client):
    assert client.get("/status").json()["summary"].startswith("lab-a: 2 of 2 free")


def test_a_lease_moves_a_board_out_of_available(client, database):
    with database.unit() as unit:
        leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN, minutes=60)
    body = client.get("/status").json()
    assert body["leases_held"] == 1
    assert body["boards"]["leased"] == 1


def test_the_expiring_window_can_be_widened(client, database):
    with database.unit() as unit:
        leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN, minutes=60)
    assert client.get("/status").json()["leases_expiring"] == 0
    assert client.get("/status?expiring_within=120").json()["leases_expiring"] == 1


def test_a_silly_window_is_refused(client):
    assert client.get("/status?expiring_within=0").status_code == 422


def test_a_waiting_request_shows_up_with_its_wait(client, database):
    with database.unit() as unit:
        queue_service.submit(unit, "team-b", "capture", "device=xcvu9p", WHEN - timedelta(hours=2))
    body = client.get("/status").json()
    assert body["queue_waiting"] == 1
    assert body["queue_longest_wait_seconds"] == 7200


def test_thermal_states_are_tallied(client, database):
    with database.unit() as unit:
        thermal_service.record(unit, "bx-14", WHEN - timedelta(seconds=20), 60_000)
        thermal_service.record(unit, "bx-14", WHEN - timedelta(seconds=5), 61_000)
    body = client.get("/status").json()
    assert body["thermal"] == {"normal": 1, "unknown": 1}


def test_a_board_out_of_calibration_makes_the_bench_unhealthy(client, database):
    with database.unit() as unit:
        inventory.add_board(
            unit,
            kintex("bx-16", location="rack-2/shelf-06/slot-2", serial="210308AA0001", outlet=12),
            WHEN - timedelta(days=1),
        )
    body = client.get("/status").json()
    assert body["calibration_overdue"] == ["bx-16"]
    assert body["healthy"] is False


def test_a_quiet_bench_is_healthy(client, database):
    with database.unit() as unit:
        thermal_service.record(unit, "bx-14", WHEN - timedelta(seconds=10), 60_000)
        thermal_service.record(unit, "bx-15", WHEN - timedelta(seconds=10), 55_000)
    assert client.get("/status").json()["healthy"] is True


def test_readiness_answers_without_a_key(client):
    body = client.get("/ready").json()
    assert body["ready"] is True
    assert body["boards"] == 2


def test_readiness_reports_the_site(client):
    assert client.get("/ready").json()["site"] == "lab-a"


def test_health_stays_the_shallow_check(client):
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert "boards" not in body
