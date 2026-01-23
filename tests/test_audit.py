from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from boardsetup import artix, kintex
from fpga.access import ApiKey
from fpga.api.app import build_app
from fpga.api.deps import now
from fpga.audit import AuditEntry
from fpga.config import BenchSettings
from fpga.errors import ValidationError
from fpga.persistence.unit import BenchDatabase
from fpga.services import audit, inventory, leasing

WHEN = datetime(2026, 2, 2, 9, 0, tzinfo=UTC)


@pytest.fixture()
def database():
    live = BenchDatabase.in_memory()
    with live.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), WHEN)
        inventory.add_board(unit, artix("bx-15"), WHEN)
    return live


@pytest.fixture()
def client(database):
    app = build_app(database, BenchSettings())
    app.dependency_overrides[now] = lambda: WHEN
    return TestClient(app)


def test_an_entry_describes_itself():
    entry = AuditEntry(
        at=WHEN,
        actor="Signal Processing",
        action="lease-taken",
        subject="bx-14",
        detail="lease-00001 for capture",
    )
    assert entry.actor == "signal-processing"
    assert entry.describe() == (
        "2026-02-02T09:00:00+00:00 signal-processing lease-taken bx-14: lease-00001 for capture"
    )


def test_audit_times_are_in_utc():
    with pytest.raises(ValidationError):
        AuditEntry(at=datetime(2026, 2, 2, 9), actor="dana", action="lease-taken")


def test_taking_a_board_leaves_a_line(database):
    with database.unit() as unit:
        leasing.acquire(unit, "bx-14", "team-a", "capture regression", WHEN)
    with database.unit() as unit:
        trail = audit.recent(unit)
    assert [entry.action for entry in trail] == ["lease-taken"]
    assert trail[0].subject == "bx-14"
    assert "capture regression" in trail[0].detail


def test_giving_it_back_leaves_another(database):
    with database.unit() as unit:
        lease = leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN)
    with database.unit() as unit:
        leasing.release(unit, lease.id, WHEN + timedelta(minutes=20), "finished")
    with database.unit() as unit:
        trail = audit.recent(unit)
    assert [entry.action for entry in trail] == ["lease-released", "lease-taken"]


def test_a_state_change_is_recorded_with_both_states(database):
    with database.unit() as unit:
        inventory.move_state(
            unit, "bx-14", "maintenance", WHEN, reason="header rework", actor="dana"
        )
    with database.unit() as unit:
        trail = audit.recent(unit)
    assert trail[0].action == "board-state"
    assert "available to maintenance" in trail[0].detail
    assert trail[0].actor == "dana"


def test_faulting_a_board_is_recorded_as_its_own_action(database):
    with database.unit() as unit:
        inventory.fault_board(unit, "bx-14", WHEN, "chain read as all ones", actor="nightly")
    with database.unit() as unit:
        trail = audit.recent(unit)
    assert trail[0].action == "board-faulted"
    assert trail[0].detail == "chain read as all ones"


def test_the_trail_can_be_read_for_one_board(database):
    with database.unit() as unit:
        leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN)
        leasing.acquire(unit, "bx-15", "team-b", "bring up", WHEN)
    with database.unit() as unit:
        for_board = audit.for_subject(unit, "bx-15")
    assert [entry.subject for entry in for_board] == ["bx-15"]


def test_the_trail_comes_back_newest_first(database):
    with database.unit() as unit:
        leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN)
    with database.unit() as unit:
        leasing.acquire(unit, "bx-15", "team-b", "bring up", WHEN + timedelta(minutes=5))
    with database.unit() as unit:
        trail = audit.recent(unit)
    assert [entry.subject for entry in trail] == ["bx-15", "bx-14"]


def test_a_limit_of_nothing_is_a_bug(database):
    with database.unit() as unit, pytest.raises(ValidationError):
        audit.recent(unit, limit=0)


def test_a_window_reads_what_happened_inside_it(database):
    with database.unit() as unit:
        audit.record(unit, WHEN - timedelta(days=2), "board-state", subject="bx-14")
        audit.record(unit, WHEN, "lease-taken", subject="bx-15")
    with database.unit() as unit:
        recent = audit.since(unit, WHEN, timedelta(hours=1))
    assert [entry.subject for entry in recent] == ["bx-15"]


def test_a_window_of_no_length_is_a_bug(database):
    with database.unit() as unit, pytest.raises(ValidationError):
        audit.since(unit, WHEN, timedelta(0))


def test_a_line_records_the_key_that_did_it(database):
    key = ApiKey(id="abc", label="nightly runner", role="operator")
    with database.unit() as unit:
        audit.record(unit, WHEN, "lease-taken", subject="bx-14", key=key)
    with database.unit() as unit:
        trail = audit.recent(unit)
    assert trail[0].actor == "nightly-runner"
    assert trail[0].key_id == "abc"


def test_a_long_detail_is_trimmed_rather_than_refused(database):
    with database.unit() as unit:
        audit.record(unit, WHEN, "board-faulted", subject="bx-14", detail="x" * 400)
    with database.unit() as unit:
        assert len(audit.recent(unit)[0].detail) == 240


def test_the_trail_is_readable_over_http(client):
    client.post("/boards/bx-14/lease", json={"holder": "team-a", "purpose": "capture"})
    body = client.get("/audit").json()
    assert body[0]["action"] == "lease-taken"
    assert body[0]["subject"] == "bx-14"


def test_the_http_trail_can_be_narrowed_to_one_board(client):
    client.post("/boards/bx-14/lease", json={"holder": "team-a", "purpose": "capture"})
    client.post("/boards/bx-15/lease", json={"holder": "team-b", "purpose": "bring up"})
    body = client.get("/audit?subject=bx-15").json()
    assert [entry["subject"] for entry in body] == ["bx-15"]


def test_the_http_trail_takes_a_limit(client):
    client.post("/boards/bx-14/lease", json={"holder": "team-a", "purpose": "capture"})
    client.post("/boards/bx-15/lease", json={"holder": "team-b", "purpose": "bring up"})
    assert len(client.get("/audit?limit=1").json()) == 1
    assert client.get("/audit?limit=0").status_code == 422
