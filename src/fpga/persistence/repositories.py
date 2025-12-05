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

from datetime import datetime

from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from fpga.artifacts.registry import ArtifactRecord
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
    ArtifactRow,
    BoardRow,
    BoardStateRow,
    JobRow,
    LeaseRow,
    SlotRow,
)
from fpga.records import board_key

__all__ = [
    "ArtifactRepository",
    "BoardRepository",
    "JobRepository",
    "LeaseRepository",
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
