"""The shapes that go over the wire, and what they refuse.

Measures cross the wire as the strings people write them as, not as numbers.
An asset tag is bx-14, a location is rack-2/shelf-04/slot-3, a part is an
ordering code. The domain objects already know how to read those, so the
schemas stay thin and there is exactly one parser for each thing rather than
one in the domain and another in the api.

Responses are built from domain objects by a classmethod on each schema. That
keeps the field mapping in one place and means a rename in the domain shows up
here as a type error rather than as a quietly missing key.
"""

from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field

from fpga.inventory.boards import Board
from fpga.inventory.state import BoardState
from fpga.jobs.models import ProgramJob
from fpga.leases.models import Lease
from fpga.thermal.alarms import ThermalStatus
from fpga.thermal.samples import ThermalSample
from fpga.thermal.supervisor import SupervisorAction

__all__ = [
    "BoardIn",
    "BoardOut",
    "JobIn",
    "JobOut",
    "LeaseIn",
    "LeaseOut",
    "SampleIn",
    "SampleOut",
    "SlotIn",
    "StateIn",
    "StatusOut",
    "SupervisorActionOut",
]


class SlotIn(BaseModel):
    """One connector on a board being written down."""

    name: str
    connector: str
    fitted: str | None = None


class BoardIn(BaseModel):
    """A board arriving from a config file or a person."""

    asset: str
    model: str
    part: str
    flash: str
    location: str
    revision: str = "a"
    slots: list[SlotIn] = Field(default_factory=list)
    adapter_serial: str | None = None
    adapter_model: str | None = None
    pdu: str | None = None
    vadj_millivolts: int = 1800
    header_voltage_mv: int = 1800
    max_tck_hz: int = 30_000_000
    notes: str = ""


class BoardOut(BaseModel):
    """A board on the way out."""

    asset: str
    model: str
    revision: str
    part: str
    device: str
    family: str
    flash: str
    location: str
    cards: list[str]
    transceivers_free: int
    adapter: str | None
    pdu: str | None
    state: str
    state_since: datetime | None = None
    state_reason: str = ""

    @classmethod
    def of(cls, board: Board, state: BoardState | None = None) -> BoardOut:
        device = board.device()
        return cls(
            asset=board.tag,
            model=board.model,
            revision=board.revision,
            part=board.part.ordering_code(),
            device=device.device,
            family=device.family,
            flash=board.flash,
            location=str(board.location),
            cards=[spec.name for spec in board.fitted_cards()],
            transceivers_free=board.transceivers_free,
            adapter=None if board.adapter is None else board.adapter.serial,
            pdu=None if board.pdu is None else str(board.pdu),
            state="unknown" if state is None else state.state,
            state_since=None if state is None else state.since,
            state_reason="" if state is None else state.reason,
        )


class StateIn(BaseModel):
    """A state change somebody is asking for."""

    state: str
    reason: str = ""
    actor: str = ""


class LeaseIn(BaseModel):
    """A request to take a board."""

    holder: str
    purpose: str
    minutes: int | None = None
    priority: int = 5
    preemptible: bool = True


class LeaseOut(BaseModel):
    """A lease on the way out."""

    id: str
    board: str
    holder: str
    purpose: str
    taken_at: datetime
    expires_at: datetime
    priority: int
    renewals: int
    preemptible: bool
    released_at: datetime | None = None
    release_reason: str = ""

    @classmethod
    def of(cls, lease: Lease) -> LeaseOut:
        return cls(
            id=lease.id,
            board=lease.board,
            holder=lease.holder,
            purpose=lease.purpose,
            taken_at=lease.taken_at,
            expires_at=lease.expires_at,
            priority=lease.priority,
            renewals=lease.renewals,
            preemptible=lease.preemptible,
            released_at=lease.released_at,
            release_reason=lease.release_reason,
        )


class JobIn(BaseModel):
    """A programming job being asked for."""

    board: str
    digest: str
    mode: str = "jtag"
    requested_by: str
    verify: bool = True


class JobOut(BaseModel):
    """A job on the way out."""

    id: str
    board: str
    digest: str
    mode: str
    requested_by: str
    state: str
    attempts: int
    verify: bool
    created_at: datetime
    started_at: datetime | None = None
    finished_at: datetime | None = None
    error: str = ""
    error_code: str = ""

    @classmethod
    def of(cls, job: ProgramJob) -> JobOut:
        return cls(
            id=job.id,
            board=job.board,
            digest=job.digest,
            mode=job.mode,
            requested_by=job.requested_by,
            state=job.state,
            attempts=job.attempts,
            verify=job.verify,
            created_at=job.created_at,
            started_at=job.started_at,
            finished_at=job.finished_at,
            error=job.error,
            error_code=job.error_code,
        )


class SampleIn(BaseModel):
    """One reading a rack controller is posting.

    The time is optional because the common case is a controller posting what
    it just read, and the server clock is the one everything else on the bench
    is stamped with. A controller replaying a backlog sends its own times.
    """

    board: str
    millidegrees: int
    at: datetime | None = None
    source: str = "sysmon"


class SampleOut(BaseModel):
    """A stored reading on the way out."""

    board: str
    at: datetime
    millidegrees: int
    celsius: float
    source: str

    @classmethod
    def of(cls, sample: ThermalSample) -> SampleOut:
        return cls(
            board=sample.board,
            at=sample.at,
            millidegrees=sample.millidegrees,
            celsius=sample.millidegrees / 1000,
            source=sample.source,
        )


class StatusOut(BaseModel):
    """Where one board stands thermally."""

    board: str
    state: str
    since: datetime
    held_for_seconds: int
    millidegrees: int | None = None
    stale: bool = False
    alarming: bool = False

    @classmethod
    def of(cls, status: ThermalStatus, moment: datetime) -> StatusOut:
        return cls(
            board=status.board,
            state=status.state,
            since=status.since,
            held_for_seconds=int(status.held_for(moment).total_seconds()),
            millidegrees=status.millidegrees,
            stale=status.stale,
            alarming=status.alarming,
        )


class SupervisorActionOut(BaseModel):
    """Something the supervisor did on a pass."""

    board: str
    action: str
    reason: str
    affected: list[str] = []

    @classmethod
    def of(cls, action: SupervisorAction) -> SupervisorActionOut:
        return cls(
            board=action.board,
            action=action.action,
            reason=action.reason,
            affected=list(action.affected),
        )
