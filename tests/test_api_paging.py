from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from boardsetup import kintex
from fpga.api.app import build_app
from fpga.api.deps import MAX_PAGE, Page, now
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory, leasing
from fpga.services import scheduling as queue_service
from fpga.services import thermal as thermal_service

WHEN = datetime(2026, 6, 8, 10, 0, tzinfo=UTC)


def board(index):
    """One of a dozen boards, named so that sorting them reads naturally."""
    shelf = (index - 101) // 6 + 1
    slot = (index - 101) % 6 + 1
    return kintex(
        f"bx-{index}",
        location=f"rack-5/shelf-{shelf:02d}/slot-{slot}",
        serial=f"2103080{index:05d}",
        outlet=index % 24 + 1,
        pdu_name=f"pdu-{index // 24}",
    )


@pytest.fixture()
def database():
    built = BenchDatabase.in_memory()
    with built.unit() as unit:
        for index in range(101, 113):
            inventory.add_board(unit, board(index), WHEN - timedelta(days=1))
    return built


@pytest.fixture()
def client(database):
    app = build_app(database, BenchSettings())
    app.dependency_overrides[now] = lambda: WHEN
    return TestClient(app)


def test_a_page_slices_what_it_is_given():
    assert Page(limit=2, offset=1).slice([1, 2, 3, 4]) == [2, 3]


def test_a_page_past_the_end_is_empty():
    assert Page(limit=5, offset=99).slice([1, 2, 3]) == []


def test_the_default_page_is_the_whole_short_listing(client):
    assert len(client.get("/boards").json()) == 12


def test_a_listing_can_be_cut_down(client):
    body = client.get("/boards?limit=5").json()
    assert [entry["asset"] for entry in body] == ["bx-101", "bx-102", "bx-103", "bx-104", "bx-105"]


def test_a_listing_can_be_skipped_into(client):
    body = client.get("/boards?limit=3&offset=9").json()
    assert [entry["asset"] for entry in body] == ["bx-110", "bx-111", "bx-112"]


def test_a_page_past_the_end_answers_with_nothing(client):
    assert client.get("/boards?offset=50").json() == []


def test_a_limit_of_zero_is_refused(client):
    assert client.get("/boards?limit=0").status_code == 422


def test_a_limit_over_the_ceiling_is_refused(client):
    assert client.get(f"/boards?limit={MAX_PAGE + 1}").status_code == 422


def test_a_negative_offset_is_refused(client):
    assert client.get("/boards?offset=-1").status_code == 422


def test_the_filters_still_apply_before_the_page(client, database):
    with database.unit() as unit:
        leasing.acquire(unit, "bx-101", "team-a", "capture", WHEN)
    body = client.get("/boards?available=true&limit=3").json()
    assert "bx-101" not in [entry["asset"] for entry in body]
    assert len(body) == 3


def test_leases_are_paged(client, database):
    with database.unit() as unit:
        for index in range(101, 105):
            leasing.acquire(unit, f"bx-{index}", "team-a", "capture", WHEN)
    assert len(client.get("/leases?limit=2").json()) == 2
    assert len(client.get("/leases?limit=2&offset=3").json()) == 1


def test_the_queue_is_paged(client, database):
    with database.unit() as unit:
        for index in range(5):
            queue_service.submit(unit, f"team-{index}", "capture", "", WHEN)
    body = client.get("/requests?limit=2&offset=2").json()
    assert [entry["holder"] for entry in body] == ["team-2", "team-3"]


def test_thermal_history_is_paged(client, database):
    with database.unit() as unit:
        for index in range(30):
            thermal_service.record(
                unit,
                "bx-101",
                WHEN - timedelta(seconds=20 * index),
                60_000 + index,
            )
    body = client.get("/boards/bx-101/thermal?limit=10").json()
    assert len(body) == 10
    assert body[0]["millidegrees"] == 60_029


def test_thermal_history_can_be_skipped_into(client, database):
    with database.unit() as unit:
        for index in range(30):
            thermal_service.record(
                unit,
                "bx-101",
                WHEN - timedelta(seconds=20 * index),
                60_000 + index,
            )
    body = client.get("/boards/bx-101/thermal?limit=5&offset=25").json()
    assert [entry["millidegrees"] for entry in body] == [60_004, 60_003, 60_002, 60_001, 60_000]
