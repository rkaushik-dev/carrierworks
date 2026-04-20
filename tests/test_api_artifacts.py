from datetime import UTC, datetime

import pytest
from fastapi.testclient import TestClient

from fpga.api.app import build_app
from fpga.api.deps import now
from fpga.artifacts.registry import ArtifactStore
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from images import bitfile

WHEN = datetime(2026, 1, 16, 8, 0, tzinfo=UTC)


@pytest.fixture()
def client(tmp_path):
    app = build_app(BenchDatabase.in_memory(), BenchSettings(artifact_root=tmp_path / "artifacts"))
    app.state.artifacts = ArtifactStore(tmp_path / "artifacts")
    app.dependency_overrides[now] = lambda: WHEN
    return TestClient(app)


def upload(client, **kwargs):
    channel = kwargs.pop("channel", "dev")
    return client.post(
        f"/artifacts?channel={channel}",
        content=bitfile(**kwargs),
        headers={"content-type": "application/octet-stream"},
    )


def test_an_upload_stores_the_image_and_records_it(client):
    response = upload(client)
    assert response.status_code == 201
    body = response.json()
    assert body["design"] == "radar_frontend"
    assert body["device"] == "xc7k325t"
    assert body["kind"] == "full"
    assert body["channel"] == "dev"
    assert len(body["digest"]) == 64


def test_uploading_the_same_image_twice_is_one_record(client):
    first = upload(client).json()
    second = upload(client).json()
    assert first["digest"] == second["digest"]
    assert len(client.get("/artifacts").json()) == 1


def test_a_partial_image_is_recorded_as_partial(client):
    body = upload(client, start=False).json()
    assert body["kind"] == "partial"


def test_something_that_is_not_a_bit_file_is_refused(client):
    response = client.post(
        "/artifacts",
        content=b"nonsense",
        headers={"content-type": "application/octet-stream"},
    )
    assert response.status_code == 422


def test_an_image_comes_back_on_its_own(client):
    digest = upload(client).json()["digest"]
    assert client.get(f"/artifacts/{digest}").json()["digest"] == digest


def test_an_image_nobody_stored_is_a_404(client):
    assert client.get(f"/artifacts/{'ab' * 32}").status_code == 404


def test_images_are_listed_by_design_and_by_channel(client):
    upload(client)
    upload(client, design="beamformer", frames=20, channel="qual")
    by_design = client.get("/artifacts?design=beamformer").json()
    assert [entry["design"] for entry in by_design] == ["beamformer"]
    by_channel = client.get("/artifacts?channel=qual").json()
    assert [entry["design"] for entry in by_channel] == ["beamformer"]


def test_an_image_with_no_evidence_cannot_be_promoted(client):
    digest = upload(client).json()["digest"]
    response = client.post(f"/artifacts/{digest}/promote", json={"channel": "qual"})
    assert response.status_code == 409
    assert "timing" in response.json()["error"]["details"]["blockers"]


def record_build(client, digest, **changes):
    payload = {
        "build_id": "nightly-1841",
        "source_revision": "9f2c11a",
        "built_at": WHEN.isoformat(),
        "constraints_digest": "c0ffee",
    }
    payload.update(changes)
    return client.post(f"/artifacts/{digest}/builds", json=payload)


def test_a_build_can_be_recorded_against_an_image(client):
    digest = upload(client).json()["digest"]
    response = record_build(client, digest)
    assert response.status_code == 201
    assert response.json()["reproducible"] is True


def test_an_image_with_a_clean_build_and_timing_goes_to_qual(client):
    digest = upload(client).json()["digest"]
    record_build(client, digest)
    response = client.post(
        f"/artifacts/{digest}/promote",
        json={"channel": "qual", "timing_closed": True},
    )
    assert response.status_code == 200
    assert response.json()["channel"] == "qual"
    assert client.get(f"/artifacts/{digest}").json()["channel"] == "qual"


def test_an_image_built_from_a_dirty_tree_stays_in_dev(client):
    digest = upload(client).json()["digest"]
    record_build(client, digest, dirty=True)
    response = client.post(
        f"/artifacts/{digest}/promote",
        json={"channel": "qual", "timing_closed": True},
    )
    assert response.status_code == 409
    assert "reproduced" in response.json()["error"]["details"]["blockers"]


def test_a_design_that_missed_timing_stays_where_it_is(client):
    digest = upload(client).json()["digest"]
    response = client.post(
        f"/artifacts/{digest}/promote",
        json={"channel": "qual", "timing_closed": False},
    )
    assert response.status_code == 409
    assert client.get(f"/artifacts/{digest}").json()["channel"] == "dev"


def test_promotion_still_goes_one_channel_at_a_time(client):
    digest = upload(client).json()["digest"]
    record_build(client, digest)
    response = client.post(
        f"/artifacts/{digest}/promote",
        json={"channel": "golden", "timing_closed": True, "approvals": ["kmoyer"]},
    )
    assert response.status_code == 409
    assert "one channel at a time" in response.json()["error"]["details"]["blockers"]


def test_the_load_counts_start_at_nothing(client):
    digest = upload(client).json()["digest"]
    assert client.get(f"/artifacts/{digest}/loads").json() == {"passed": 0, "failed": 0}


def test_the_load_counts_are_a_404_for_an_image_nobody_has(client):
    assert client.get(f"/artifacts/{'cd' * 32}/loads").status_code == 404
