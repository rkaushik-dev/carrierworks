from datetime import UTC, datetime, timedelta

import pytest

from boardsetup import kintex
from fpga.artifacts.registry import ArtifactRecord
from fpga.config import BenchSettings
from fpga.errors import ValidationError
from fpga.inventory.state import BoardState
from fpga.jobs.models import ProgramJob
from fpga.leases.models import Lease
from fpga.persistence.engine import build_engine, create_schema, session_factory, session_scope
from fpga.persistence.mappers import (
    artifact_from_row,
    artifact_to_row,
    board_from_row,
    board_to_row,
    job_from_row,
    job_to_row,
    lease_from_row,
    lease_to_row,
    state_from_row,
    state_to_row,
)
from fpga.persistence.models import ArtifactRow, BoardRow, JobRow, LeaseRow

NOW = datetime(2025, 12, 3, 11, 0, tzinfo=UTC)
DIGEST = "ab" * 32


@pytest.fixture()
def factory(tmp_path):
    settings = BenchSettings(database_url=f"sqlite+pysqlite:///{tmp_path / 'bench.db'}")
    engine = build_engine(settings)
    create_schema(engine)
    return session_factory(engine)


def lease():
    return Lease(
        id="lease-00001",
        board="bx-14",
        holder="team-a",
        purpose="capture",
        taken_at=NOW,
        expires_at=NOW + timedelta(hours=1),
    )


def job():
    return ProgramJob(
        id="job-00001",
        board="bx-14",
        digest=DIGEST,
        mode="jtag",
        requested_by="team-a",
        created_at=NOW,
    )


def record():
    return ArtifactRecord(
        digest=DIGEST,
        design="radar_frontend",
        device="xc7k325t",
        length=3_000_000,
        kind="full",
        channel="qual",
        source="radar.bit",
        tool_version="2024.1",
        tags=("keep", "nightly"),
    )


def test_a_board_round_trips_in_memory():
    original = kintex("bx-14")
    assert board_from_row(board_to_row(original)).describe() == original.describe()


def test_a_board_round_trips_through_the_database(factory):
    original = kintex("bx-14")
    with session_scope(factory) as session:
        session.add(board_to_row(original))
    with session_scope(factory) as session:
        found = board_from_row(session.get(BoardRow, "bx-14"))
    assert found.describe() == original.describe()
    assert found.adapter is not None
    assert found.adapter.serial == "210308AB1234"
    assert str(found.pdu) == "pdu-a/outlet-07"


def test_a_bare_board_round_trips_without_a_cable_or_an_outlet(factory):
    original = kintex("bx-14", serial=None, outlet=None, slots=())
    with session_scope(factory) as session:
        session.add(board_to_row(original))
    with session_scope(factory) as session:
        found = board_from_row(session.get(BoardRow, "bx-14"))
    assert found.adapter is None
    assert found.pdu is None
    assert found.slots == ()


def test_the_fitted_cards_survive_the_trip(factory):
    with session_scope(factory) as session:
        session.add(board_to_row(kintex("bx-14")))
    with session_scope(factory) as session:
        found = board_from_row(session.get(BoardRow, "bx-14"))
    assert [spec.name for spec in found.fitted_cards()] == ["fmc-adc-4ch-14b"]
    assert found.slot("fmc1").empty


def test_a_state_round_trips_and_keeps_its_zone(factory):
    original = BoardState(state="faulted", since=NOW, reason="chain", actor="nightly")
    with session_scope(factory) as session:
        session.add(board_to_row(kintex("bx-14")))
        session.add(state_to_row("bx-14", original))
    with session_scope(factory) as session:
        row = session.get(BoardRow, "bx-14").state
        found = state_from_row(row)
    assert found == original
    assert found.since.tzinfo is not None


def test_the_available_flag_is_written_for_the_queries_that_read_it(factory):
    with session_scope(factory) as session:
        session.add(board_to_row(kintex("bx-14")))
        session.add(state_to_row("bx-14", BoardState(state="available", since=NOW)))
    with session_scope(factory) as session:
        assert session.get(BoardRow, "bx-14").state.is_available


def test_a_lease_round_trips(factory):
    with session_scope(factory) as session:
        session.add(board_to_row(kintex("bx-14")))
        session.add(lease_to_row(lease()))
    with session_scope(factory) as session:
        found = lease_from_row(session.get(LeaseRow, "lease-00001"))
    assert found == lease()


def test_a_released_lease_keeps_its_reason(factory):
    closed = lease().release(NOW + timedelta(minutes=20), "finished")
    with session_scope(factory) as session:
        session.add(board_to_row(kintex("bx-14")))
        session.add(lease_to_row(closed))
    with session_scope(factory) as session:
        found = lease_from_row(session.get(LeaseRow, "lease-00001"))
    assert found.released
    assert found.release_reason == "finished"
    assert found.released_at.tzinfo is not None


def test_a_job_round_trips(factory):
    with session_scope(factory) as session:
        session.add(board_to_row(kintex("bx-14")))
        session.add(job_to_row(job()))
    with session_scope(factory) as session:
        found = job_from_row(session.get(JobRow, "job-00001"))
    assert found == job()


def test_a_finished_job_keeps_its_error_and_its_times(factory):
    failed = job().start(NOW).failed(NOW + timedelta(minutes=1), "cable", code="chain")
    with session_scope(factory) as session:
        session.add(board_to_row(kintex("bx-14")))
        session.add(job_to_row(failed))
    with session_scope(factory) as session:
        found = job_from_row(session.get(JobRow, "job-00001"))
    assert found.error == "cable"
    assert found.error_code == "chain"
    assert found.duration() == timedelta(minutes=1)


def test_a_held_job_keeps_the_time_it_is_held_until(factory):
    hold = NOW + timedelta(seconds=45)
    with session_scope(factory) as session:
        session.add(board_to_row(kintex("bx-14")))
        session.add(job_to_row(job(), not_before=hold))
    with session_scope(factory) as session:
        assert session.get(JobRow, "job-00001").not_before is not None


def test_an_artifact_round_trips_with_its_tags(factory):
    with session_scope(factory) as session:
        session.add(artifact_to_row(record(), stored_at=NOW))
    with session_scope(factory) as session:
        found = artifact_from_row(session.get(ArtifactRow, DIGEST))
    assert found.tags == ("keep", "nightly")
    assert found.channel == "qual"
    assert found.tool_version == "2024.1"


def test_an_artifact_with_no_tags_comes_back_with_none(factory):
    with session_scope(factory) as session:
        session.add(artifact_to_row(record().tagged(), stored_at=NOW))
    with session_scope(factory) as session:
        found = artifact_from_row(session.get(ArtifactRow, DIGEST))
    assert found.tags == ("keep", "nightly")


@pytest.mark.parametrize(
    "build",
    [
        lambda: state_to_row(
            "bx-14", BoardState(state="available", since=NOW.replace(tzinfo=None))
        ),
        lambda: lease_to_row(
            Lease(
                id="lease-1",
                board="bx-14",
                holder="team-a",
                purpose="capture",
                taken_at=NOW.replace(tzinfo=None),
                expires_at=NOW + timedelta(hours=1),
            )
        ),
    ],
)
def test_a_naive_timestamp_is_refused_on_the_way_in(build):
    with pytest.raises(ValidationError):
        build()
