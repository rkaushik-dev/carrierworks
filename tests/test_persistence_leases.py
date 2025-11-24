from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from fpga.config import BenchSettings
from fpga.persistence.engine import build_engine, create_schema, session_factory, session_scope
from fpga.persistence.models import BoardRow, JobRow, LeaseRow

NOW = datetime(2025, 11, 21, 8, 0, tzinfo=UTC)
DIGEST = "ab" * 32


@pytest.fixture()
def factory(tmp_path):
    settings = BenchSettings(database_url=f"sqlite+pysqlite:///{tmp_path / 'bench.db'}")
    engine = build_engine(settings)
    create_schema(engine)
    live = session_factory(engine)
    with session_scope(live) as session:
        session.add(
            BoardRow(
                tag="bx-14",
                model="rx-carrier-b",
                part="xc7k325t-2ffg900i",
                device="xc7k325t",
                flash="n25q128a13",
                rack="rack-2",
                shelf=4,
                slot=3,
            )
        )
    return live


def lease_row(lease_id="lease-00001", released=None, priority=5, hours=1):
    return LeaseRow(
        id=lease_id,
        board_tag="bx-14",
        holder="team-a",
        purpose="capture regression",
        taken_at=NOW,
        expires_at=NOW + timedelta(hours=hours),
        priority=priority,
        released_at=released,
    )


def job_row(job_id="job-00001", digest=DIGEST, state="queued"):
    return JobRow(
        id=job_id,
        board_tag="bx-14",
        digest=digest,
        mode="jtag",
        requested_by="team-a",
        state=state,
        created_at=NOW,
    )


def test_a_lease_round_trips(factory):
    with session_scope(factory) as session:
        session.add(lease_row())
    with session_scope(factory) as session:
        found = session.get(LeaseRow, "lease-00001")
        assert found.holder == "team-a"
        assert found.priority == 5
        assert found.released_at is None


def test_one_board_cannot_have_two_open_leases(factory):
    with session_scope(factory) as session:
        session.add(lease_row())
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(lease_row(lease_id="lease-00002"))


def test_a_closed_lease_lets_the_next_one_in(factory):
    with session_scope(factory) as session:
        session.add(lease_row(released=NOW + timedelta(minutes=30)))
    with session_scope(factory) as session:
        session.add(lease_row(lease_id="lease-00002"))
    with session_scope(factory) as session:
        assert len(session.scalars(select(LeaseRow)).all()) == 2


def test_any_number_of_closed_leases_are_allowed(factory):
    with session_scope(factory) as session:
        for index in range(4):
            session.add(
                lease_row(lease_id=f"lease-{index}", released=NOW + timedelta(minutes=index + 1))
            )
    with session_scope(factory) as session:
        assert len(session.scalars(select(LeaseRow)).all()) == 4


def test_a_priority_off_the_scale_is_refused(factory):
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(lease_row(priority=42))


def test_a_lease_that_expires_before_it_starts_is_refused(factory):
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(lease_row(hours=-1))


def test_a_lease_on_a_board_that_is_not_there_is_refused(factory):
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        row = lease_row()
        row.board_tag = "bx-99"
        session.add(row)


def test_a_board_holding_a_lease_cannot_simply_be_deleted(factory):
    with session_scope(factory) as session:
        session.add(lease_row())
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.delete(session.get(BoardRow, "bx-14"))


def test_a_job_round_trips(factory):
    with session_scope(factory) as session:
        session.add(job_row())
    with session_scope(factory) as session:
        found = session.get(JobRow, "job-00001")
        assert found.mode == "jtag"
        assert found.attempts == 0
        assert found.verify
        assert found.not_before is None


def test_a_job_with_a_short_digest_is_refused(factory):
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(job_row(digest="abcdef"))


def test_negative_attempts_are_refused(factory):
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        row = job_row()
        row.attempts = -1
        session.add(row)


def test_jobs_are_found_by_board_and_state(factory):
    with session_scope(factory) as session:
        session.add(job_row())
        session.add(job_row(job_id="job-00002", state="passed"))
    with session_scope(factory) as session:
        queued = session.scalars(
            select(JobRow).where(JobRow.board_tag == "bx-14", JobRow.state == "queued")
        ).all()
        assert [row.id for row in queued] == ["job-00001"]


def test_jobs_are_found_by_digest(factory):
    with session_scope(factory) as session:
        session.add(job_row())
    with session_scope(factory) as session:
        found = session.scalars(select(JobRow).where(JobRow.digest == DIGEST)).all()
        assert len(found) == 1
