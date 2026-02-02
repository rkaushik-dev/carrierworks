from datetime import UTC, datetime

import pytest
from fastapi.testclient import TestClient

from fpga.api.app import build_app
from fpga.api.deps import now
from fpga.artifacts.registry import ArtifactStore
from fpga.bitstream.bitfile import build_bitfile
from fpga.bitstream.packets import SYNC_WORD, type1, type2, words_to_bytes
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase

WHEN = datetime(2026, 2, 8, 7, 0, tzinfo=UTC)

TIMING = """
Design Timing Summary
Design : radar_frontend
| WNS(ns) | TNS(ns) | TNS Failing Endpoints | TNS Total Endpoints | WHS(ns) | THS(ns) | THS Failing Endpoints | THS Total Endpoints |
| 0.412   | 0.000   | 0                     | 118423              | 0.137   | 0.000   | 0                     | 118423              |
"""

TIGHT = """
| WNS(ns) | TNS(ns) | TNS Failing Endpoints | TNS Total Endpoints | WHS(ns) | THS(ns) | THS Failing Endpoints | THS Total Endpoints |
| 0.021   | 0.000   | 0                     | 118423              | 0.005   | 0.000   | 0                     | 118423              |
"""

FAILING = """
| WNS(ns) | TNS(ns)  | TNS Failing Endpoints | TNS Total Endpoints | WHS(ns) | THS(ns) | THS Failing Endpoints | THS Total Endpoints |
| -0.311  | -14.200  | 96                    | 118423              | 0.137   | 0.000   | 0                     | 118423              |
"""

UTILIZATION = """
+----------------------------+-------+-------+-----------+-------+
|          Site Type         |  Used | Fixed | Available | Util% |
+----------------------------+-------+-------+-----------+-------+
| Slice LUTs                 | 45123 |     0 |    203800 | 22.14 |
| Slice Registers            | 61234 |     0 |    407600 | 15.02 |
+----------------------------+-------+-------+-----------+-------+
"""


def bitfile(frames=16):
    words = [
        0xFFFFFFFF,
        SYNC_WORD,
        type1(opcode=2, register=12, word_count=1),
        0x03651093,
        type1(opcode=2, register=2, word_count=0),
        type2(opcode=2, word_count=frames),
        *range(frames),
        type1(opcode=2, register=4, word_count=1),
        5,
    ]
    return build_bitfile(
        design="radar_frontend;Version=2024.1",
        part="7k325tffg900",
        date="2026/02/07",
        time="02:00:00",
        data=words_to_bytes(words),
    )


@pytest.fixture()
def client(tmp_path):
    app = build_app(BenchDatabase.in_memory(), BenchSettings(artifact_root=tmp_path / "artifacts"))
    app.state.artifacts = ArtifactStore(tmp_path / "artifacts")
    app.dependency_overrides[now] = lambda: WHEN
    return TestClient(app)


def upload(client, frames=16):
    response = client.post(
        "/artifacts",
        content=bitfile(frames),
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
    ingest(client, digest, timing=FAILING)
    assert client.get("/designs/radar_frontend/baseline").json() is None


def test_a_later_build_does_not_move_the_baseline(client):
    first = upload(client)
    ingest(client, first)
    second = upload(client, frames=20)
    ingest(client, second, timing=TIGHT)
    assert client.get("/designs/radar_frontend/baseline").json()["digest"] == first


def test_the_baseline_can_be_pinned(client):
    first = upload(client)
    ingest(client, first)
    second = upload(client, frames=20)
    ingest(client, second, timing=TIGHT)
    body = client.post("/designs/radar_frontend/baseline", json={"digest": second}).json()
    assert body["digest"] == second
    assert client.get("/designs/radar_frontend/baseline").json()["digest"] == second


def test_a_tighter_build_is_reported_as_a_regression(client):
    first = upload(client)
    ingest(client, first)
    second = upload(client, frames=20)
    ingest(client, second, timing=TIGHT)
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
    ingest(client, digest, timing=FAILING)
    response = client.post(
        f"/artifacts/{digest}/promote",
        json={"channel": "qual", "timing_closed": True},
    )
    assert response.status_code == 409
    assert "did not close timing" in response.json()["error"]["details"]["blockers"]


def test_regressions_for_an_image_with_no_report_are_a_404(client):
    digest = upload(client)
    assert client.get(f"/artifacts/{digest}/regressions").status_code == 404
