from datetime import UTC, datetime, timedelta

import pytest

from boardsetup import artix, kintex
from fpga.artifacts.registry import ArtifactRecord
from fpga.config import BenchSettings
from fpga.errors import NotFound
from fpga.inventory.state import BoardState
from fpga.jobs.models import ProgramJob
from fpga.leases.models import Lease
from fpga.persistence.engine import build_engine, create_schema, session_factory, session_scope
from fpga.persistence.repositories import (
    ArtifactRepository,
    BoardRepository,
    JobRepository,
    LeaseRepository,
)

NOW = datetime(2025, 12, 8, 9, 0, tzinfo=UTC)
DIGEST = "ab" * 32


@pytest.fixture()
def factory(tmp_path):
    settings = BenchSettings(database_url=f"sqlite+pysqlite:///{tmp_path / 'bench.db'}")
    engine = build_engine(settings)
    create_schema(engine)
    return session_factory(engine)


def available(when=NOW):
    return BoardState(state="available", since=when)


def lease(lease_id="lease-00001", board="bx-14", holder="team-a", hours=1):
    return Lease(
        id=lease_id,
        board=board,
        holder=holder,
        purpose="capture",
        taken_at=NOW,
        expires_at=NOW + timedelta(hours=hours),
    )


def job(job_id="job-00001", board="bx-14", digest=DIGEST):
    return ProgramJob(
        id=job_id,
        board=board,
        digest=digest,
        mode="jtag",
        requested_by="team-a",
        created_at=NOW,
    )


def record(digest=DIGEST, channel="dev", design="radar_frontend"):
    return ArtifactRecord(
        digest=digest,
        design=design,
        device="xc7k325t",
        length=3_000_000,
        kind="full",
        channel=channel,
    )


def test_a_board_goes_in_and_comes_back(factory):
    with session_scope(factory) as session:
        BoardRepository(session).add(kintex("bx-14"), available())
    with session_scope(factory) as session:
        found = BoardRepository(session).get("BX-0014")
        assert found.model == "rx-carrier-b"
        assert BoardRepository(session).state("bx-14").usable


def test_a_board_nobody_added_is_a_not_found(factory):
    with session_scope(factory) as session, pytest.raises(NotFound):
        BoardRepository(session).get("bx-99")


def test_only_available_boards_come_back_from_the_available_query(factory):
    with session_scope(factory) as session:
        repo = BoardRepository(session)
        repo.add(kintex("bx-14"), available())
        repo.add(artix("bx-15"), BoardState(state="faulted", since=NOW, reason="chain"))
    with session_scope(factory) as session:
        assert [board.tag for board in BoardRepository(session).available()] == ["bx-14"]
        assert len(BoardRepository(session).all()) == 2


def test_a_state_change_is_written_back(factory):
    with session_scope(factory) as session:
        BoardRepository(session).add(kintex("bx-14"), available())
    with session_scope(factory) as session:
        BoardRepository(session).save_state(
            "bx-14",
            BoardState(state="leased", since=NOW + timedelta(minutes=5)),
        )
    with session_scope(factory) as session:
        assert BoardRepository(session).state("bx-14").state == "leased"
        assert BoardRepository(session).available() == ()


def test_a_changed_board_is_written_back_with_its_slots(factory):
    with session_scope(factory) as session:
        BoardRepository(session).add(kintex("bx-14"), available())
    with session_scope(factory) as session:
        repo = BoardRepository(session)
        repo.replace(repo.get("bx-14").with_card("fmc1", "fmc-adc-2ch-16b"))
    with session_scope(factory) as session:
        found = BoardRepository(session).get("bx-14")
        assert len(found.fitted_cards()) == 2
        assert found.slot("fmc1").fitted == "fmc-adc-2ch-16b"


def test_boards_come_back_by_rack(factory):
    with session_scope(factory) as session:
        repo = BoardRepository(session)
        repo.add(kintex("bx-14", location="rack-2/shelf-04/slot-3"), available())
        repo.add(artix("bx-15", location="rack-4/shelf-01/slot-1"), available())
    with session_scope(factory) as session:
        assert [board.tag for board in BoardRepository(session).in_rack("rack-4")] == ["bx-15"]


def test_a_removed_board_is_gone(factory):
    with session_scope(factory) as session:
        BoardRepository(session).add(kintex("bx-14"), available())
    with session_scope(factory) as session:
        BoardRepository(session).remove("bx-14")
    with session_scope(factory) as session:
        assert BoardRepository(session).all() == ()


def test_a_lease_is_found_for_its_board(factory):
    with session_scope(factory) as session:
        BoardRepository(session).add(kintex("bx-14"), available())
        LeaseRepository(session).add(lease())
    with session_scope(factory) as session:
        found = LeaseRepository(session).for_board("bx-14", NOW + timedelta(minutes=1))
        assert found is not None
        assert found.holder == "team-a"


def test_an_expired_lease_does_not_hold_a_board(factory):
    with session_scope(factory) as session:
        BoardRepository(session).add(kintex("bx-14"), available())
        LeaseRepository(session).add(lease(hours=1))
    with session_scope(factory) as session:
        assert LeaseRepository(session).for_board("bx-14", NOW + timedelta(hours=2)) is None
        assert LeaseRepository(session).active(NOW + timedelta(hours=2)) == ()


def test_a_released_lease_stops_holding_its_board(factory):
    with session_scope(factory) as session:
        BoardRepository(session).add(kintex("bx-14"), available())
        LeaseRepository(session).add(lease())
    with session_scope(factory) as session:
        repo = LeaseRepository(session)
        repo.save(repo.get("lease-00001").release(NOW + timedelta(minutes=10), "finished"))
    with session_scope(factory) as session:
        assert LeaseRepository(session).for_board("bx-14", NOW + timedelta(minutes=11)) is None
        assert len(LeaseRepository(session).history("bx-14")) == 1


def test_what_a_holder_has(factory):
    with session_scope(factory) as session:
        repo = BoardRepository(session)
        repo.add(kintex("bx-14"), available())
        repo.add(artix("bx-15"), available())
        LeaseRepository(session).add(lease())
        LeaseRepository(session).add(lease(lease_id="lease-00002", board="bx-15", holder="team-b"))
    with session_scope(factory) as session:
        held = LeaseRepository(session).held_by("team-a", NOW + timedelta(minutes=1))
        assert [entry.board for entry in held] == ["bx-14"]


def test_a_lease_nobody_issued_is_a_not_found(factory):
    with session_scope(factory) as session, pytest.raises(NotFound):
        LeaseRepository(session).get("lease-09999")


def test_a_job_goes_in_and_comes_back(factory):
    with session_scope(factory) as session:
        BoardRepository(session).add(kintex("bx-14"), available())
        JobRepository(session).add(job())
    with session_scope(factory) as session:
        found = JobRepository(session).get("job-00001")
        assert found.mode == "jtag"
        assert found.state == "queued"


def test_a_held_job_is_not_offered_until_its_backoff_passes(factory):
    with session_scope(factory) as session:
        BoardRepository(session).add(kintex("bx-14"), available())
        JobRepository(session).add(job(), not_before=NOW + timedelta(seconds=30))
    with session_scope(factory) as session:
        repo = JobRepository(session)
        assert repo.queued(NOW) == ()
        assert len(repo.queued(NOW + timedelta(seconds=31))) == 1


def test_a_running_job_comes_back_from_the_running_query(factory):
    with session_scope(factory) as session:
        BoardRepository(session).add(kintex("bx-14"), available())
        JobRepository(session).add(job())
    with session_scope(factory) as session:
        repo = JobRepository(session)
        repo.save(repo.get("job-00001").start(NOW))
    with session_scope(factory) as session:
        repo = JobRepository(session)
        assert [entry.id for entry in repo.running()] == ["job-00001"]
        assert repo.queued(NOW) == ()


def test_jobs_come_back_by_board_and_by_image(factory):
    with session_scope(factory) as session:
        BoardRepository(session).add(kintex("bx-14"), available())
        JobRepository(session).add(job())
        JobRepository(session).add(job(job_id="job-00002", digest="cd" * 32))
    with session_scope(factory) as session:
        repo = JobRepository(session)
        assert len(repo.for_board("bx-14")) == 2
        assert [entry.id for entry in repo.for_digest(DIGEST)] == ["job-00001"]


def test_an_artifact_goes_in_and_moves_channel(factory):
    with session_scope(factory) as session:
        ArtifactRepository(session).add(record(), stored_at=NOW)
    with session_scope(factory) as session:
        repo = ArtifactRepository(session)
        repo.save(repo.get(DIGEST).with_channel("qual").tagged("keep"))
    with session_scope(factory) as session:
        found = ArtifactRepository(session).get(DIGEST)
        assert found.channel == "qual"
        assert found.tags == ("keep",)


def test_artifacts_come_back_by_design_and_channel(factory):
    with session_scope(factory) as session:
        repo = ArtifactRepository(session)
        repo.add(record(), stored_at=NOW)
        repo.add(record(digest="cd" * 32, channel="qual", design="beamformer"), stored_at=NOW)
    with session_scope(factory) as session:
        repo = ArtifactRepository(session)
        assert [entry.design for entry in repo.in_channel("qual")] == ["beamformer"]
        assert len(repo.for_design("radar_frontend")) == 1


def test_an_artifact_nobody_stored_is_a_not_found(factory):
    with session_scope(factory) as session, pytest.raises(NotFound):
        ArtifactRepository(session).get("ef" * 32)
