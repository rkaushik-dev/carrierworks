"""Between the objects the bench works in and the rows the database keeps.

Written by hand, both directions, one function each way. The alternative is
hanging orm mappings off the domain classes, and then every module that touches
a board drags in sqlalchemy and every test needs a database.

The one thing to be careful about is timestamps. Sqlite has no timezone aware
type, so a datetime that went in with a zone comes back without one, and a
naive datetime compared against an aware one raises rather than being wrong,
which at least is loud. Every read puts the zone back, and every write refuses
a naive value, so the wrongness cannot start here.
"""

from __future__ import annotations

from datetime import UTC, datetime

from fpga.artifacts.registry import ArtifactRecord
from fpga.catalog.partnumber import parse_part_number
from fpga.errors import ValidationError
from fpga.ids import AssetTag
from fpga.inventory.boards import Board, BoardSlot
from fpga.inventory.state import BoardState
from fpga.jobs.models import ProgramJob
from fpga.jtag.adapters import AdapterUnit
from fpga.leases.models import Lease
from fpga.locations import BenchLocation, PduChannel
from fpga.persistence.models import ArtifactRow, BoardRow, BoardStateRow, JobRow, LeaseRow, SlotRow

__all__ = [
    "artifact_from_row",
    "artifact_to_row",
    "board_from_row",
    "board_to_row",
    "job_from_row",
    "job_to_row",
    "lease_from_row",
    "lease_to_row",
    "state_from_row",
    "state_to_row",
]


def _aware(value: datetime) -> datetime:
    """A timestamp read back from the database, with its zone put back."""
    return value if value.tzinfo is not None else value.replace(tzinfo=UTC)


def _check_aware(value: datetime, field: str) -> datetime:
    if value.tzinfo is None:
        raise ValidationError("that timestamp has no timezone", field=field)
    return value


def board_to_row(board: Board) -> BoardRow:
    row = BoardRow(
        tag=board.tag,
        model=board.model,
        revision=board.revision,
        part=board.part.ordering_code().lower(),
        device=board.device().device,
        flash=board.flash,
        rack=board.location.rack,
        shelf=board.location.shelf,
        slot=board.location.slot,
        adapter_serial=None if board.adapter is None else board.adapter.serial,
        adapter_model=None if board.adapter is None else board.adapter.model,
        adapter_port="" if board.adapter is None else board.adapter.port,
        pdu=None if board.pdu is None else board.pdu.pdu,
        outlet=None if board.pdu is None else board.pdu.outlet,
        vadj_millivolts=board.vadj_millivolts,
        header_voltage_mv=board.header_voltage_mv,
        max_tck_hz=board.max_tck_hz,
        notes=board.notes,
    )
    row.slots = [
        SlotRow(name=slot.name, connector=slot.connector, fitted=slot.fitted)
        for slot in board.slots
    ]
    return row


def board_from_row(row: BoardRow) -> Board:
    adapter = None
    if row.adapter_serial and row.adapter_model:
        adapter = AdapterUnit(
            serial=row.adapter_serial,
            model=row.adapter_model,
            port=row.adapter_port,
        )
    pdu = None
    if row.pdu and row.outlet is not None:
        pdu = PduChannel(row.pdu, row.outlet)
    return Board(
        asset=AssetTag.parse(row.tag),
        model=row.model,
        part=parse_part_number(row.part),
        flash=row.flash,
        location=BenchLocation(row.rack, row.shelf, row.slot),
        revision=row.revision,
        slots=tuple(
            BoardSlot(name=slot.name, connector=slot.connector, fitted=slot.fitted)
            for slot in row.slots
        ),
        adapter=adapter,
        pdu=pdu,
        vadj_millivolts=row.vadj_millivolts,
        header_voltage_mv=row.header_voltage_mv,
        max_tck_hz=row.max_tck_hz,
        notes=row.notes,
    )


def state_to_row(tag: str, state: BoardState) -> BoardStateRow:
    return BoardStateRow(
        board_tag=tag,
        state=state.state,
        since=_check_aware(state.since, "since"),
        reason=state.reason,
        actor=state.actor,
        is_available=state.usable,
    )


def state_from_row(row: BoardStateRow) -> BoardState:
    return BoardState(
        state=row.state,
        since=_aware(row.since),
        reason=row.reason,
        actor=row.actor,
    )


def lease_to_row(lease: Lease) -> LeaseRow:
    return LeaseRow(
        id=lease.id,
        board_tag=lease.board,
        holder=lease.holder,
        purpose=lease.purpose,
        taken_at=_check_aware(lease.taken_at, "taken_at"),
        expires_at=_check_aware(lease.expires_at, "expires_at"),
        priority=lease.priority,
        renewals=lease.renewals,
        preemptible=lease.preemptible,
        released_at=None if lease.released_at is None else _check_aware(lease.released_at, "at"),
        release_reason=lease.release_reason,
    )


def lease_from_row(row: LeaseRow) -> Lease:
    return Lease(
        id=row.id,
        board=row.board_tag,
        holder=row.holder,
        purpose=row.purpose,
        taken_at=_aware(row.taken_at),
        expires_at=_aware(row.expires_at),
        priority=row.priority,
        renewals=row.renewals,
        preemptible=row.preemptible,
        released_at=None if row.released_at is None else _aware(row.released_at),
        release_reason=row.release_reason,
    )


def job_to_row(job: ProgramJob, *, not_before: datetime | None = None) -> JobRow:
    return JobRow(
        id=job.id,
        board_tag=job.board,
        digest=job.digest,
        mode=job.mode,
        requested_by=job.requested_by,
        state=job.state,
        attempts=job.attempts,
        verify=job.verify,
        created_at=_check_aware(job.created_at, "created_at"),
        started_at=None if job.started_at is None else _check_aware(job.started_at, "at"),
        finished_at=None if job.finished_at is None else _check_aware(job.finished_at, "at"),
        not_before=not_before,
        error=job.error,
        error_code=job.error_code,
        clocks=job.clocks,
        lease_id=job.lease or None,
    )


def job_from_row(row: JobRow) -> ProgramJob:
    return ProgramJob(
        id=row.id,
        board=row.board_tag,
        digest=row.digest,
        mode=row.mode,
        requested_by=row.requested_by,
        created_at=_aware(row.created_at),
        state=row.state,
        attempts=row.attempts,
        verify=row.verify,
        started_at=None if row.started_at is None else _aware(row.started_at),
        finished_at=None if row.finished_at is None else _aware(row.finished_at),
        error=row.error,
        error_code=row.error_code,
        clocks=row.clocks,
        lease=row.lease_id or "",
    )


def artifact_to_row(record: ArtifactRecord, *, stored_at: datetime) -> ArtifactRow:
    return ArtifactRow(
        digest=record.digest,
        design=record.design,
        device=record.device,
        length_bytes=record.length,
        kind=record.kind,
        channel=record.channel,
        source=record.source,
        tool_version=record.tool_version,
        stored_at=_check_aware(stored_at, "stored_at"),
        tags=",".join(record.tags),
    )


def artifact_from_row(row: ArtifactRow) -> ArtifactRecord:
    return ArtifactRecord(
        digest=row.digest,
        design=row.design,
        device=row.device,
        length=row.length_bytes,
        kind=row.kind,
        channel=row.channel,
        source=row.source,
        tool_version=row.tool_version,
        stored_at=_aware(row.stored_at).isoformat(timespec="seconds"),
        tags=tuple(tag for tag in row.tags.split(",") if tag),
    )
