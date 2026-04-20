from datetime import UTC, datetime

import pytest
from fastapi.testclient import TestClient

from fpga.api.app import build_app
from fpga.api.deps import now
from fpga.artifacts.registry import ArtifactStore
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from images import FAILING_TIMING, TIGHT_TIMING, TIMING, UTILIZATION, bitfile

WHEN = datetime(2026, 2, 8, 7, 0, tzinfo=UTC)


@pytest.fixture()
def client(tmp_path):
    app = build_app(BenchDatabase.in_memory(), BenchSettings(artifact_root=tmp_path / "artifacts"))
    app.state.artifacts = ArtifactStore(tmp_path / "artifacts")
    app.dependency_overrides[now] = lambda: WHEN
    return TestClient(app)


def upload(client, frames=16):
    response = client.post(
        "/artifacts",
        content=bitfile(frames=frames),
        headers={"content-type": "application/octet-stream"},
    )
    return response.json()["digest"]


def ingest(client, digest, timing=TIMING, utilization=UTILIZATION):
    return client.post(
        f"/artifacts/{digest}/reports",
        json={"timing": timing, "utilization": utilization},
    )


def build(client, digest):
    return client.post(
        f"/artifacts/{digest}/builds",
        json={
            "build_id": f"nightly-{digest[:4]}",
            "source_revision": "9f2c11a",
            "built_at": WHEN.isoformat(),
            "constraints_digest": "c0ffee",
        },
    )


def test_reports_are_read_in_and_stored(client):
    digest = upload(client)
    response = ingest(client, digest)
    assert response.status_code == 201
    body = response.json()
    assert body["closed"] is True
    assert body["wns_ps"] == 412
    assert body["luts"] == 45123


def test_the_stored_report_reads_back(client):
    digest = upload(client)
    ingest(client, digest)
    body = client.get(f"/artifacts/{digest}/reports").json()
    assert body["whs_ps"] == 137
    assert body["registers"] == 61234


def test_an_image_with_no_reports_read_is_a_404(client):
    digest = upload(client)
    assert client.get(f"/artifacts/{digest}/reports").status_code == 404


def test_reports_for_an_image_nobody_stored_is_a_404(client):
    assert ingest(client, "ab" * 32).status_code == 404


def test_reading_the_reports_twice_is_refused(client):
    digest = upload(client)
    ingest(client, digest)
    assert ingest(client, digest).status_code == 422


def test_a_report_that_is_not_one_is_refused(client):
    digest = upload(client)
    response = ingest(client, digest, timing="nothing to see here")
    assert response.status_code == 422


def test_the_first_closing_build_becomes_the_baseline(client):
    digest = upload(client)
    ingest(client, digest)
    baseline = client.get("/designs/radar_frontend/baseline").json()
    assert baseline["digest"] == digest


def test_a_build_that_missed_timing_does_not_become_the_baseline(client):
    digest = upload(client)
    ingest(client, digest, timing=FAILING_TIMING)
    assert client.get("/designs/radar_frontend/baseline").json() is None


def test_a_later_build_does_not_move_the_baseline(client):
    first = upload(client)
    ingest(client, first)
    second = upload(client, frames=20)
    ingest(client, second, timing=TIGHT_TIMING)
    assert client.get("/designs/radar_frontend/baseline").json()["digest"] == first


def test_the_baseline_can_be_pinned(client):
    first = upload(client)
    ingest(client, first)
    second = upload(client, frames=20)
    ingest(client, second, timing=TIGHT_TIMING)
    body = client.post("/designs/radar_frontend/baseline", json={"digest": second}).json()
    assert body["digest"] == second
    assert client.get("/designs/radar_frontend/baseline").json()["digest"] == second


def test_a_tighter_build_is_reported_as_a_regression(client):
    first = upload(client)
    ingest(client, first)
    second = upload(client, frames=20)
    ingest(client, second, timing=TIGHT_TIMING)
    body = client.get(f"/artifacts/{second}/regressions").json()
    assert not body["clean"]
    assert any("slack fell" in line for line in body["regressions"])


def test_the_baseline_does_not_regress_against_itself(client):
    digest = upload(client)
    ingest(client, digest)
    body = client.get(f"/artifacts/{digest}/regressions").json()
    assert body["clean"]


def test_promotion_reads_the_stored_timing_rather_than_the_caller(client):
    digest = upload(client)
    build(client, digest)
    ingest(client, digest)
    response = client.post(f"/artifacts/{digest}/promote", json={"channel": "qual"})
    assert response.status_code == 200
    assert client.get(f"/artifacts/{digest}").json()["channel"] == "qual"


def test_a_caller_cannot_claim_timing_a_report_contradicts(client):
    digest = upload(client)
    build(client, digest)
    ingest(client, digest, timing=FAILING_TIMING)
    response = client.post(
        f"/artifacts/{digest}/promote",
        json={"channel": "qual", "timing_closed": True},
    )
    assert response.status_code == 409
    assert "did not close timing" in response.json()["error"]["details"]["blockers"]


def test_regressions_for_an_image_with_no_report_are_a_404(client):
    digest = upload(client)
    assert client.get(f"/artifacts/{digest}/regressions").status_code == 404
