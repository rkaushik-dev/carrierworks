from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from boardsetup import artix, kintex
from fpga.api.app import build_app
from fpga.api.deps import now
from fpga.artifacts.registry import ArtifactRecord
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory

WHEN = datetime(2026, 1, 20, 9, 0, tzinfo=UTC)
DIGEST = "ab" * 32
OTHER = "cd" * 32


class Clock:
    def __init__(self):
        self.moment = WHEN

    def __call__(self):
        return self.moment

    def tick(self, **delta):
        self.moment = self.moment + timedelta(**delta)
        return self.moment


def record(digest=DIGEST, channel="qual"):
    return ArtifactRecord(
        digest=digest,
        design="radar_frontend",
        device="xc7k325t",
        length=3_000_000,
        kind="full",
        channel=channel,
    )


@pytest.fixture()
def database():
    live = BenchDatabase.in_memory()
    with live.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), WHEN)
        inventory.add_board(unit, artix("bx-15"), WHEN)
        unit.artifacts.add(record(), stored_at=WHEN)
        unit.artifacts.add(record(digest=OTHER), stored_at=WHEN)
    return live


@pytest.fixture()
def clock():
    return Clock()


@pytest.fixture()
def client(database, clock):
    app = build_app(database, BenchSettings())
    app.dependency_overrides[now] = clock
    return TestClient(app)


def submit(client, board="bx-14", digest=DIGEST, mode="jtag"):
    return client.post(
        "/jobs",
        json={
            "board": board,
            "digest": digest,
            "mode": mode,
            "requested_by": "team-a",
        },
    )


def test_a_job_goes_on_the_queue(client):
    response = submit(client)
    assert response.status_code == 201
    body = response.json()
    assert body["id"] == "job-00001"
    assert body["state"] == "queued"
    assert body["verify"] is True


def test_job_numbers_carry_on(client):
    submit(client)
    assert submit(client, board="bx-15").json()["id"] == "job-00002"


def test_a_job_for_a_board_nobody_has_is_a_404(client):
    assert submit(client, board="bx-99").status_code == 404


def test_a_job_for_an_image_nobody_stored_is_a_404(client):
    assert submit(client, digest="ef" * 32).status_code == 404


def test_a_mode_nobody_has_is_refused(client):
    assert submit(client, mode="serial").status_code == 422


def test_jobs_come_back_by_state_and_by_image(client):
    submit(client)
    submit(client, board="bx-15", digest=OTHER)
    queued = client.get("/jobs?state=queued").json()
    assert len(queued) == 2
    for_image = client.get(f"/jobs?digest={OTHER}").json()
    assert [entry["board"] for entry in for_image] == ["bx-15"]


def test_one_job_comes_back_on_its_own(client):
    job_id = submit(client).json()["id"]
    assert client.get(f"/jobs/{job_id}").json()["board"] == "bx-14"


def test_a_job_nobody_asked_for_is_a_404(client):
    assert client.get("/jobs/job-09999").status_code == 404


def test_the_ready_list_offers_one_job_per_board(client):
    submit(client)
    submit(client)
    ready = client.get("/jobs/ready").json()
    assert [entry["id"] for entry in ready] == ["job-00001"]


def test_the_ready_list_offers_jobs_for_different_boards(client):
    submit(client)
    submit(client, board="bx-15")
    ready = client.get("/jobs/ready").json()
    assert [entry["board"] for entry in ready] == ["bx-14", "bx-15"]


def test_a_running_job_keeps_its_board_out_of_the_ready_list(client, database):
    submit(client)
    submit(client)
    with database.unit() as unit:
        unit.jobs.save(unit.jobs.get("job-00001").start(WHEN))
    assert client.get("/jobs/ready").json() == []


def test_a_second_job_on_a_board_is_refused_while_one_is_running(client, database):
    submit(client)
    with database.unit() as unit:
        unit.jobs.save(unit.jobs.get("job-00001").start(WHEN))
    response = submit(client)
    assert response.status_code == 409
    assert response.json()["error"]["details"]["job"] == "job-00001"


def test_a_cancelled_job_leaves_the_queue(client):
    job_id = submit(client).json()["id"]
    body = client.post(f"/jobs/{job_id}/cancel?reason=board+went+away").json()
    assert body["state"] == "cancelled"
    assert body["error"] == "board went away"
    assert client.get("/jobs/ready").json() == []


def test_a_cancelled_job_cannot_be_cancelled_again(client):
    job_id = submit(client).json()["id"]
    client.post(f"/jobs/{job_id}/cancel")
    assert client.post(f"/jobs/{job_id}/cancel").status_code == 422


def test_a_board_lists_what_has_been_asked_of_it(client):
    submit(client)
    submit(client, board="bx-15")
    listed = client.get("/boards/bx-14/jobs").json()
    assert [entry["id"] for entry in listed] == ["job-00001"]
    assert client.get("/boards/bx-99/jobs").status_code == 404


def test_a_held_job_is_not_offered_until_its_backoff_passes(client, database, clock):
    submit(client)
    with database.unit() as unit:
        job = unit.jobs.get("job-00001")
        failed = job.start(WHEN).failed(WHEN, "cable", code="chain", retryable=True)
        unit.jobs.save(failed, not_before=WHEN + timedelta(seconds=30))
    assert client.get("/jobs/ready").json() == []
    clock.tick(seconds=31)
    assert [entry["id"] for entry in client.get("/jobs/ready").json()] == ["job-00001"]
