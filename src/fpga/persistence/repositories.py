"""The queries the bench actually runs, each in one place.

Every repository takes a session and hands back domain objects. No sql escapes
into a service and no service assembles a query, which is what keeps the http
layer and the command line reading the same rows the same way.

The queries are the ones the bench has needed, not a general purpose interface.
Adding a method here when something new needs asking is cheap; letting callers
build their own filters is how two of them end up disagreeing about whether a
released lease counts as active.
"""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from fpga.access import ApiKey
from fpga.artifacts.provenance import Provenance
from fpga.artifacts.registry import ArtifactRecord
from fpga.audit import AuditEntry
from fpga.capabilities import RequirementSet
from fpga.errors import NotFound
from fpga.inventory.boards import Board
from fpga.inventory.state import BoardState
from fpga.jobs.models import ProgramJob
from fpga.leases.models import Lease
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
from fpga.persistence.models import (
    ApiKeyRow,
    ArtifactRow,
    AuditRow,
    BoardRow,
    BoardStateRow,
    BuildReportRow,
    JobRow,
    LeaseRow,
    ProvenanceRow,
    RequestRow,
    SlotRow,
    ThermalRow,
)
from fpga.records import board_key
from fpga.reports.baselines import BuildReport
from fpga.reports.timing import TimingSummary
from fpga.reports.utilization import SiteUsage, Utilization
from fpga.scheduling.queue import Request
from fpga.thermal.samples import ThermalLog, ThermalSample

__all__ = [
    "ApiKeyRepository",
    "ArtifactRepository",
    "AuditRepository",
    "BuildReportRepository",
    "ProvenanceRepository",
    "RequestRepository",
    "BoardRepository",
    "JobRepository",
    "LeaseRepository",
    "ThermalRepository",
]


class BoardRepository:
    """Boards and the state that hangs off them."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def _row(self, tag: str) -> BoardRow:
        row = self.session.get(BoardRow, board_key(tag))
        if row is None:
            raise NotFound("board", board_key(tag))
        return row

    def add(self, board: Board, state: BoardState) -> Board:
        row = board_to_row(board)
        row.state = state_to_row(board.tag, state)
        self.session.add(row)
        return board

    def get(self, tag: str) -> Board:
        return board_from_row(self._row(tag))

    def state(self, tag: str) -> BoardState:
        row = self._row(tag)
        if row.state is None:
            raise NotFound("board state", row.tag)
        return state_from_row(row.state)

    def save_state(self, tag: str, state: BoardState) -> BoardState:
        row = self._row(tag)
        if row.state is None:
            row.state = state_to_row(row.tag, state)
        else:
            row.state.state = state.state
            row.state.since = state.since
            row.state.reason = state.reason
            row.state.actor = state.actor
            row.state.is_available = state.usable
        return state

    def replace(self, board: Board) -> Board:
        """Write a changed board back, slots and all.

        The columns are copied onto the row that is already in the session
        rather than by adding a second row with the same key. Building a fresh
        row and handing it over looks tidier and produces an identity conflict
        the moment the session flushes.

        The slots are cleared, flushed, and then rebuilt. Clearing and adding
        in one go leaves the delete and the insert in the same flush, and the
        database applies the insert first and trips the unique index.
        """
        row = self._row(board.tag)
        row.model = board.model
        row.revision = board.revision
        row.part = board.part.ordering_code().lower()
        row.device = board.device().device
        row.flash = board.flash
        row.rack = board.location.rack
        row.shelf = board.location.shelf
        row.slot = board.location.slot
        row.adapter_serial = None if board.adapter is None else board.adapter.serial
        row.adapter_model = None if board.adapter is None else board.adapter.model
        row.adapter_port = "" if board.adapter is None else board.adapter.port
        row.pdu = None if board.pdu is None else board.pdu.pdu
        row.outlet = None if board.pdu is None else board.pdu.outlet
        row.vadj_millivolts = board.vadj_millivolts
        row.header_voltage_mv = board.header_voltage_mv
        row.max_tck_hz = board.max_tck_hz
        row.notes = board.notes
        row.slots.clear()
        self.session.flush()
        for slot in board.slots:
            row.slots.append(SlotRow(name=slot.name, connector=slot.connector, fitted=slot.fitted))
        return board

    def all(self) -> tuple[Board, ...]:
        rows = self.session.scalars(
            select(BoardRow).options(selectinload(BoardRow.slots)).order_by(BoardRow.tag)
        ).all()
        return tuple(board_from_row(row) for row in rows)

    def available(self) -> tuple[Board, ...]:
        rows = self.session.scalars(
            select(BoardRow)
            .join(BoardStateRow)
            .where(BoardStateRow.is_available.is_(True))
            .options(selectinload(BoardRow.slots))
            .order_by(BoardRow.tag)
        ).all()
        return tuple(board_from_row(row) for row in rows)

    def in_rack(self, rack: str) -> tuple[Board, ...]:
        rows = self.session.scalars(
            select(BoardRow)
            .where(BoardRow.rack == rack.strip().lower())
            .options(selectinload(BoardRow.slots))
            .order_by(BoardRow.tag)
        ).all()
        return tuple(board_from_row(row) for row in rows)

    def remove(self, tag: str) -> None:
        self.session.delete(self._row(tag))


class LeaseRepository:
    """Leases, live and closed."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def add(self, lease: Lease) -> Lease:
        self.session.add(lease_to_row(lease))
        return lease

    def get(self, lease_id: str) -> Lease:
        row = self.session.get(LeaseRow, lease_id)
        if row is None:
            raise NotFound("lease", lease_id)
        return lease_from_row(row)

    def save(self, lease: Lease) -> Lease:
        row = self.session.get(LeaseRow, lease.id)
        if row is None:
            raise NotFound("lease", lease.id)
        row.expires_at = lease.expires_at
        row.renewals = lease.renewals
        row.released_at = lease.released_at
        row.release_reason = lease.release_reason
        return lease

    def for_board(self, tag: str, now: datetime) -> Lease | None:
        row = self.session.scalars(
            select(LeaseRow)
            .where(
                LeaseRow.board_tag == board_key(tag),
                LeaseRow.released_at.is_(None),
                LeaseRow.expires_at > now,
            )
            .order_by(LeaseRow.taken_at.desc())
        ).first()
        return None if row is None else lease_from_row(row)

    def active(self, now: datetime) -> tuple[Lease, ...]:
        rows = self.session.scalars(
            select(LeaseRow)
            .where(LeaseRow.released_at.is_(None), LeaseRow.expires_at > now)
            .order_by(LeaseRow.taken_at)
        ).all()
        return tuple(lease_from_row(row) for row in rows)

    def held_by(self, holder: str, now: datetime) -> tuple[Lease, ...]:
        rows = self.session.scalars(
            select(LeaseRow)
            .where(
                LeaseRow.holder == holder.strip().lower(),
                LeaseRow.released_at.is_(None),
                LeaseRow.expires_at > now,
            )
            .order_by(LeaseRow.taken_at)
        ).all()
        return tuple(lease_from_row(row) for row in rows)

    def history(self, tag: str) -> tuple[Lease, ...]:
        rows = self.session.scalars(
            select(LeaseRow).where(LeaseRow.board_tag == board_key(tag)).order_by(LeaseRow.taken_at)
        ).all()
        return tuple(lease_from_row(row) for row in rows)


class JobRepository:
    """Programming jobs."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def add(self, job: ProgramJob, *, not_before: datetime | None = None) -> ProgramJob:
        self.session.add(job_to_row(job, not_before=not_before))
        return job

    def get(self, job_id: str) -> ProgramJob:
        row = self.session.get(JobRow, job_id)
        if row is None:
            raise NotFound("job", job_id)
        return job_from_row(row)

    def save(self, job: ProgramJob, *, not_before: datetime | None = None) -> ProgramJob:
        row = self.session.get(JobRow, job.id)
        if row is None:
            raise NotFound("job", job.id)
        row.state = job.state
        row.attempts = job.attempts
        row.started_at = job.started_at
        row.finished_at = job.finished_at
        row.error = job.error
        row.error_code = job.error_code
        row.clocks = job.clocks
        row.lease_id = job.lease or None
        row.not_before = not_before
        return job

    def queued(self, now: datetime) -> tuple[ProgramJob, ...]:
        """Queued jobs whose backoff has passed, oldest first."""
        rows = self.session.scalars(
            select(JobRow)
            .where(
                JobRow.state == "queued",
                (JobRow.not_before.is_(None)) | (JobRow.not_before <= now),
            )
            .order_by(JobRow.created_at, JobRow.id)
        ).all()
        return tuple(job_from_row(row) for row in rows)

    def for_board(self, tag: str) -> tuple[ProgramJob, ...]:
        rows = self.session.scalars(
            select(JobRow).where(JobRow.board_tag == board_key(tag)).order_by(JobRow.created_at)
        ).all()
        return tuple(job_from_row(row) for row in rows)

    def for_digest(self, digest: str) -> tuple[ProgramJob, ...]:
        rows = self.session.scalars(
            select(JobRow).where(JobRow.digest == digest).order_by(JobRow.created_at)
        ).all()
        return tuple(job_from_row(row) for row in rows)

    def running(self) -> tuple[ProgramJob, ...]:
        rows = self.session.scalars(
            select(JobRow).where(JobRow.state == "running").order_by(JobRow.started_at)
        ).all()
        return tuple(job_from_row(row) for row in rows)


class ArtifactRepository:
    """The image registry's rows, which sit beside the files themselves."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def add(self, record: ArtifactRecord, *, stored_at: datetime) -> ArtifactRecord:
        self.session.add(artifact_to_row(record, stored_at=stored_at))
        return record

    def get(self, digest: str) -> ArtifactRecord:
        row = self.session.get(ArtifactRow, digest)
        if row is None:
            raise NotFound("artifact", digest)
        return artifact_from_row(row)

    def save(self, record: ArtifactRecord) -> ArtifactRecord:
        row = self.session.get(ArtifactRow, record.digest)
        if row is None:
            raise NotFound("artifact", record.digest)
        row.channel = record.channel
        row.tags = ",".join(record.tags)
        return record

    def for_design(self, design: str) -> tuple[ArtifactRecord, ...]:
        rows = self.session.scalars(
            select(ArtifactRow)
            .where(ArtifactRow.design == design.strip().lower())
            .order_by(ArtifactRow.stored_at.desc())
        ).all()
        return tuple(artifact_from_row(row) for row in rows)

    def in_channel(self, channel: str) -> tuple[ArtifactRecord, ...]:
        rows = self.session.scalars(
            select(ArtifactRow)
            .where(ArtifactRow.channel == channel.strip().lower())
            .order_by(ArtifactRow.stored_at.desc())
        ).all()
        return tuple(artifact_from_row(row) for row in rows)


class ProvenanceRepository:
    """The builds that produced each image."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def add(self, build: Provenance) -> Provenance:
        self.session.add(
            ProvenanceRow(
                digest=build.digest,
                design=build.design,
                source_revision=build.source_revision,
                build_id=build.build_id,
                built_at=datetime.fromisoformat(build.built_at),
                tool_version=build.tool_version,
                constraints_digest=build.constraints_digest,
                builder=build.builder,
                branch=build.branch,
                dirty=build.dirty,
            )
        )
        return build

    def for_digest(self, digest: str) -> tuple[Provenance, ...]:
        rows = self.session.scalars(
            select(ProvenanceRow)
            .where(ProvenanceRow.digest == digest)
            .order_by(ProvenanceRow.built_at, ProvenanceRow.id)
        ).all()
        return tuple(
            Provenance(
                digest=row.digest,
                design=row.design,
                source_revision=row.source_revision,
                build_id=row.build_id,
                built_at=row.built_at.isoformat(),
                tool_version=row.tool_version,
                constraints_digest=row.constraints_digest,
                builder=row.builder,
                branch=row.branch,
                dirty=row.dirty,
            )
            for row in rows
        )

    def disagreements(self, digest: str) -> tuple[str, ...]:
        """Builds of one image whose inputs differ from the first recorded."""
        builds = self.for_digest(digest)
        if len(builds) < 2:
            return ()
        first = builds[0]
        return tuple(build.build_id for build in builds[1:] if not build.same_source_as(first))


class ApiKeyRepository:
    """Credentials, stored as digests."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def add(self, key: ApiKey, secret_digest: str, now: datetime) -> ApiKey:
        self.session.add(
            ApiKeyRow(
                id=key.id,
                label=key.label,
                secret_digest=secret_digest,
                role=key.role,
                team=key.team,
                created_at=now,
                active=key.active,
            )
        )
        return key

    def _of(self, row: ApiKeyRow) -> ApiKey:
        return ApiKey(
            id=row.id,
            label=row.label,
            role=row.role,
            team=row.team,
            active=row.active,
        )

    def by_digest(self, secret_digest: str) -> ApiKey | None:
        row = self.session.scalars(
            select(ApiKeyRow).where(ApiKeyRow.secret_digest == secret_digest)
        ).first()
        return None if row is None else self._of(row)

    def get(self, key_id: str) -> ApiKey:
        row = self.session.get(ApiKeyRow, key_id)
        if row is None:
            raise NotFound("api key", key_id)
        return self._of(row)

    def touch(self, key_id: str, now: datetime) -> None:
        """Record that a key was used, which is how a stale one is spotted."""
        row = self.session.get(ApiKeyRow, key_id)
        if row is not None:
            row.last_used_at = now

    def deactivate(self, key_id: str) -> ApiKey:
        row = self.session.get(ApiKeyRow, key_id)
        if row is None:
            raise NotFound("api key", key_id)
        row.active = False
        return self._of(row)

    def all(self) -> tuple[ApiKey, ...]:
        rows = self.session.scalars(select(ApiKeyRow).order_by(ApiKeyRow.created_at)).all()
        return tuple(self._of(row) for row in rows)


class AuditRepository:
    """The append only trail."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def add(self, entry: AuditEntry) -> AuditEntry:
        self.session.add(
            AuditRow(
                at=entry.at,
                actor=entry.actor,
                action=entry.action,
                subject=entry.subject,
                detail=entry.detail,
                key_id=entry.key_id,
            )
        )
        return entry

    def _of(self, row: AuditRow) -> AuditEntry:
        return AuditEntry(
            at=row.at if row.at.tzinfo is not None else row.at.replace(tzinfo=UTC),
            actor=row.actor,
            action=row.action,
            subject=row.subject,
            detail=row.detail,
            key_id=row.key_id,
        )

    def recent(self, limit: int = 50) -> tuple[AuditEntry, ...]:
        rows = self.session.scalars(
            select(AuditRow).order_by(AuditRow.at.desc(), AuditRow.id.desc()).limit(limit)
        ).all()
        return tuple(self._of(row) for row in rows)

    def for_subject(self, subject: str, limit: int = 50) -> tuple[AuditEntry, ...]:
        rows = self.session.scalars(
            select(AuditRow)
            .where(AuditRow.subject == subject.strip().lower())
            .order_by(AuditRow.at.desc(), AuditRow.id.desc())
            .limit(limit)
        ).all()
        return tuple(self._of(row) for row in rows)

    def since(self, when: datetime) -> tuple[AuditEntry, ...]:
        rows = self.session.scalars(
            select(AuditRow).where(AuditRow.at >= when).order_by(AuditRow.at, AuditRow.id)
        ).all()
        return tuple(self._of(row) for row in rows)


class BuildReportRepository:
    """The numbers out of each build's timing and utilization reports."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def add(self, report: BuildReport, *, read_at: datetime) -> BuildReport:
        self.session.add(
            BuildReportRow(
                digest=report.digest,
                design=report.design,
                read_at=read_at,
                wns_ps=report.timing.wns_ps,
                tns_ps=report.timing.tns_ps,
                setup_failing=report.timing.setup_failing,
                setup_total=report.timing.setup_total,
                whs_ps=report.timing.whs_ps,
                ths_ps=report.timing.ths_ps,
                hold_failing=report.timing.hold_failing,
                hold_total=report.timing.hold_total,
                luts=report.utilization.luts,
                registers=report.utilization.registers,
                block_ram=report.utilization.block_ram,
                dsp=report.utilization.dsp,
            )
        )
        return report

    def _of(self, row: BuildReportRow) -> BuildReport:
        """Back out of the row, with the utilization rows rebuilt.

        The report only stores the four counts the bench compares on, so the
        available column is not kept and the rebuilt rows carry the used count
        as their own capacity. Percentages off a rebuilt report are therefore
        meaningless, which is why the comparison reads the counts.
        """
        timing = TimingSummary(
            wns_ps=row.wns_ps,
            tns_ps=row.tns_ps,
            setup_failing=row.setup_failing,
            setup_total=row.setup_total,
            whs_ps=row.whs_ps,
            ths_ps=row.ths_ps,
            hold_failing=row.hold_failing,
            hold_total=row.hold_total,
            design=row.design,
        )
        utilization = Utilization(
            rows=(
                SiteUsage(name="Slice LUTs", used=row.luts, available=max(row.luts, 1)),
                SiteUsage(
                    name="Slice Registers",
                    used=row.registers,
                    available=max(row.registers, 1),
                ),
                SiteUsage(
                    name="Block RAM Tile",
                    used=row.block_ram,
                    available=max(row.block_ram, 1),
                ),
                SiteUsage(name="DSPs", used=row.dsp, available=max(row.dsp, 1)),
            ),
            design=row.design,
        )
        return BuildReport(
            digest=row.digest,
            design=row.design,
            at=row.read_at if row.read_at.tzinfo is not None else row.read_at.replace(tzinfo=UTC),
            timing=timing,
            utilization=utilization,
        )

    def for_digest(self, digest: str) -> BuildReport | None:
        row = self.session.scalars(
            select(BuildReportRow).where(BuildReportRow.digest == digest)
        ).first()
        return None if row is None else self._of(row)

    def for_design(self, design: str) -> tuple[BuildReport, ...]:
        rows = self.session.scalars(
            select(BuildReportRow)
            .where(BuildReportRow.design == design.strip().lower())
            .order_by(BuildReportRow.read_at)
        ).all()
        return tuple(self._of(row) for row in rows)

    def baseline(self, design: str) -> BuildReport | None:
        row = self.session.scalars(
            select(BuildReportRow).where(
                BuildReportRow.design == design.strip().lower(),
                BuildReportRow.is_baseline.is_(True),
            )
        ).first()
        return None if row is None else self._of(row)

    def set_baseline(self, digest: str) -> BuildReport:
        """Pin one build as the design's baseline, unpinning whatever was.

        One baseline per design, so the old one is cleared in the same
        transaction. Two baselines would make a regression report depend on
        which row came back first.
        """
        row = self.session.scalars(
            select(BuildReportRow).where(BuildReportRow.digest == digest)
        ).first()
        if row is None:
            raise NotFound("build report", digest)
        for other in self.session.scalars(
            select(BuildReportRow).where(BuildReportRow.design == row.design)
        ).all():
            other.is_baseline = other.digest == digest
        return self._of(row)


class ThermalRepository:
    """Temperature readings, written one at a time and read in windows."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def record(self, sample: ThermalSample) -> ThermalSample:
        self.session.add(
            ThermalRow(
                board=sample.board,
                at=sample.at,
                millidegrees=sample.millidegrees,
                source=sample.source,
            )
        )
        return sample

    def _of(self, row: ThermalRow) -> ThermalSample:
        return ThermalSample(
            board=row.board,
            at=row.at if row.at.tzinfo is not None else row.at.replace(tzinfo=UTC),
            millidegrees=row.millidegrees,
            source=row.source,
        )

    def latest(self, board: str) -> ThermalSample | None:
        row = self.session.scalars(
            select(ThermalRow)
            .where(ThermalRow.board == board.strip().lower())
            .order_by(ThermalRow.at.desc(), ThermalRow.id.desc())
            .limit(1)
        ).first()
        return None if row is None else self._of(row)

    def since(self, when: datetime, board: str | None = None) -> tuple[ThermalSample, ...]:
        query = select(ThermalRow).where(ThermalRow.at >= when)
        if board is not None:
            query = query.where(ThermalRow.board == board.strip().lower())
        rows = self.session.scalars(query.order_by(ThermalRow.at, ThermalRow.id)).all()
        return tuple(self._of(row) for row in rows)

    def load(self, since: datetime, *, keep: int = 512) -> ThermalLog:
        """Every reading since a moment, back in a log the supervisor can read.

        The log is the same one the in memory bench uses, so nothing above
        this line knows whether the readings came from a database or from a
        poller that has been running for ten minutes.
        """
        log = ThermalLog(keep=keep)
        for sample in self.since(since):
            log.record(sample)
        return log

    def prune(self, before: datetime) -> int:
        """Drop everything older than a moment, and say how many that was."""
        rows = self.session.scalars(select(ThermalRow).where(ThermalRow.at < before)).all()
        for row in rows:
            self.session.delete(row)
        return len(rows)

    def count(self, board: str | None = None) -> int:
        query = select(ThermalRow)
        if board is not None:
            query = query.where(ThermalRow.board == board.strip().lower())
        return len(self.session.scalars(query).all())


def _aware(when: datetime) -> datetime:
    """A stored timestamp with its zone back on, for the sqlite that drops it."""
    return when if when.tzinfo is not None else when.replace(tzinfo=UTC)


class RequestRepository:
    """The waiting list, ordered the way the dispatcher serves it."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def next_id(self) -> str:
        """The next request number, carried on from what is already stored."""
        highest = 0
        for row in self.session.scalars(select(RequestRow)).all():
            tail = row.id.rsplit("-", 1)[-1]
            if tail.isdigit():
                highest = max(highest, int(tail))
        return f"req-{highest + 1:04d}"

    def _row(self, request_id: str) -> RequestRow:
        row = self.session.get(RequestRow, request_id.strip().lower())
        if row is None:
            raise NotFound("request", request_id)
        return row

    def _of(self, row: RequestRow) -> Request:
        return Request(
            id=row.id,
            holder=row.holder,
            purpose=row.purpose,
            requirements=RequirementSet.parse(row.requirements),
            submitted_at=_aware(row.submitted_at),
            priority=row.priority,
            minutes=row.minutes,
            preemptible=row.preemptible,
            needs_hands=row.needs_hands,
            cancelled_at=None if row.cancelled_at is None else _aware(row.cancelled_at),
            satisfied_at=None if row.satisfied_at is None else _aware(row.satisfied_at),
        )

    def add(self, request: Request, *, board: str = "") -> Request:
        self.session.add(
            RequestRow(
                id=request.id,
                holder=request.holder,
                purpose=request.purpose,
                requirements=",".join(item.text for item in request.requirements),
                submitted_at=request.submitted_at,
                priority=request.priority,
                minutes=request.minutes,
                preemptible=request.preemptible,
                needs_hands=request.needs_hands,
                cancelled_at=request.cancelled_at,
                satisfied_at=request.satisfied_at,
                assigned_board=board,
            )
        )
        self.session.flush()
        return request

    def get(self, request_id: str) -> Request:
        return self._of(self._row(request_id))

    def save(self, request: Request, *, board: str | None = None) -> Request:
        row = self._row(request.id)
        row.cancelled_at = request.cancelled_at
        row.satisfied_at = request.satisfied_at
        row.priority = request.priority
        row.minutes = request.minutes
        if board is not None:
            row.assigned_board = board
        self.session.flush()
        return request

    def board_for(self, request_id: str) -> str:
        """Which board a satisfied request got, or nothing while it waits."""
        return self._row(request_id).assigned_board

    def all(self) -> tuple[Request, ...]:
        rows = self.session.scalars(select(RequestRow).order_by(RequestRow.submitted_at)).all()
        return tuple(self._of(row) for row in rows)

    def open(self) -> tuple[Request, ...]:
        rows = self.session.scalars(
            select(RequestRow)
            .where(RequestRow.satisfied_at.is_(None), RequestRow.cancelled_at.is_(None))
            .order_by(RequestRow.submitted_at)
        ).all()
        return tuple(self._of(row) for row in rows)

    def for_holder(self, holder: str) -> tuple[Request, ...]:
        rows = self.session.scalars(
            select(RequestRow)
            .where(RequestRow.holder == holder.strip().lower())
            .order_by(RequestRow.submitted_at)
        ).all()
        return tuple(self._of(row) for row in rows)

    def ordered(self, now: datetime) -> tuple[Request, ...]:
        """Open requests, highest priority first and longest waiting next."""
        waiting = self.open()
        return tuple(
            sorted(
                waiting,
                key=lambda request: (
                    request.priority,
                    -request.waited(now).total_seconds(),
                    request.submitted_at,
                ),
            )
        )
