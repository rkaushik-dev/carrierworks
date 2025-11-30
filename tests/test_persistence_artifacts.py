from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from fpga.config import BenchSettings
from fpga.persistence.engine import build_engine, create_schema, session_factory, session_scope
from fpga.persistence.models import ArtifactRow, BuildReportRow, ProvenanceRow

NOW = datetime(2025, 11, 27, 6, 0, tzinfo=UTC)
DIGEST = "ab" * 32


@pytest.fixture()
def factory(tmp_path):
    settings = BenchSettings(database_url=f"sqlite+pysqlite:///{tmp_path / 'bench.db'}")
    engine = build_engine(settings)
    create_schema(engine)
    return session_factory(engine)


def artifact(digest=DIGEST, channel="dev", length=3_000_000):
    return ArtifactRow(
        digest=digest,
        design="radar_frontend",
        device="xc7k325t",
        length_bytes=length,
        kind="full",
        channel=channel,
        stored_at=NOW,
    )


def build(digest=DIGEST, build_id="nightly-1841", revision="9f2c11a"):
    return ProvenanceRow(
        digest=digest,
        design="radar_frontend",
        source_revision=revision,
        build_id=build_id,
        built_at=NOW,
        constraints_digest="c0ffee",
    )


def report(digest=DIGEST, wns=400):
    return BuildReportRow(
        digest=digest,
        design="radar_frontend",
        read_at=NOW,
        wns_ps=wns,
        tns_ps=0,
        whs_ps=100,
        ths_ps=0,
        setup_total=100_000,
        hold_total=100_000,
        luts=45_000,
        registers=61_000,
    )


def test_an_artifact_round_trips(factory):
    with session_scope(factory) as session:
        session.add(artifact())
    with session_scope(factory) as session:
        found = session.get(ArtifactRow, DIGEST)
        assert found.design == "radar_frontend"
        assert found.channel == "dev"
        assert found.tags == ""


def test_a_short_digest_is_refused(factory):
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(artifact(digest="abcdef"))


def test_an_empty_image_is_refused(factory):
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(artifact(length=0))


def test_two_builds_of_one_image_are_both_kept(factory):
    with session_scope(factory) as session:
        row = artifact()
        row.builds.append(build())
        row.builds.append(build(build_id="rebuild-2", revision="a" * 40))
        session.add(row)
    with session_scope(factory) as session:
        found = session.get(ArtifactRow, DIGEST)
        assert [entry.build_id for entry in found.builds] == ["nightly-1841", "rebuild-2"]


def test_the_same_build_cannot_be_recorded_twice(factory):
    with session_scope(factory) as session:
        row = artifact()
        row.builds.append(build())
        session.add(row)
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(build())


def test_provenance_needs_an_image_to_hang_off(factory):
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(build(digest="cd" * 32))


def test_a_report_hangs_off_its_image(factory):
    with session_scope(factory) as session:
        row = artifact()
        row.reports.append(report())
        session.add(row)
    with session_scope(factory) as session:
        found = session.get(ArtifactRow, DIGEST)
        assert found.reports[0].wns_ps == 400
        assert not found.reports[0].is_baseline


def test_one_image_has_one_report(factory):
    with session_scope(factory) as session:
        row = artifact()
        row.reports.append(report())
        session.add(row)
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        session.add(report(wns=100))


def test_negative_failing_counts_are_refused(factory):
    with pytest.raises(IntegrityError), session_scope(factory) as session:
        row = artifact()
        entry = report()
        entry.setup_failing = -1
        row.reports.append(entry)
        session.add(row)


def test_deleting_an_image_takes_its_builds_and_report(factory):
    with session_scope(factory) as session:
        row = artifact()
        row.builds.append(build())
        row.reports.append(report())
        session.add(row)
    with session_scope(factory) as session:
        session.delete(session.get(ArtifactRow, DIGEST))
    with session_scope(factory) as session:
        assert session.scalars(select(ProvenanceRow)).all() == []
        assert session.scalars(select(BuildReportRow)).all() == []


def test_images_are_found_by_channel_and_design(factory):
    with session_scope(factory) as session:
        session.add(artifact())
        session.add(artifact(digest="cd" * 32, channel="qual"))
    with session_scope(factory) as session:
        qualified = session.scalars(select(ArtifactRow).where(ArtifactRow.channel == "qual")).all()
        assert [row.digest for row in qualified] == ["cd" * 32]
        both = session.scalars(
            select(ArtifactRow).where(ArtifactRow.design == "radar_frontend")
        ).all()
        assert len(both) == 2


def test_a_baseline_report_is_flagged_rather_than_moved(factory):
    with session_scope(factory) as session:
        row = artifact()
        entry = report()
        entry.is_baseline = True
        row.reports.append(entry)
        session.add(row)
    with session_scope(factory) as session:
        baselines = session.scalars(
            select(BuildReportRow).where(BuildReportRow.is_baseline.is_(True))
        ).all()
        assert [entry.digest for entry in baselines] == [DIGEST]


def test_builds_are_found_by_revision(factory):
    with session_scope(factory) as session:
        row = artifact()
        row.builds.append(build())
        session.add(row)
    with session_scope(factory) as session:
        found = session.scalars(
            select(ProvenanceRow).where(ProvenanceRow.source_revision == "9f2c11a")
        ).all()
        assert len(found) == 1
        assert found[0].built_at.replace(tzinfo=UTC) == NOW
        assert found[0].built_at.replace(tzinfo=UTC) < NOW + timedelta(seconds=1)
