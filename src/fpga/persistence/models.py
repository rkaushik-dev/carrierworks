"""The tables the bench keeps, and the constraints the database enforces.

Two rules the application already enforces are enforced here as well, on
purpose. One board per slot and one active lease per board are the invariants
the whole bench rests on, and the second writer, whenever there is one, will
not be going through the application. A unique index costs nothing and turns a
race into an error.

Timestamps are stored with their zone. Sqlite does not really have a timezone
aware type, but declaring the intent means the postgres this eventually moves
to will do the right thing, and the mappers refuse a naive datetime long before
it reaches here.

Column widths are what the values actually are. An asset tag is at most ten
characters and a digest is exactly sixty four, and writing that down catches a
whole class of mistake at insert time rather than at read time.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    UniqueConstraint,
    text,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

__all__ = [
    "ApiKeyRow",
    "ArtifactRow",
    "Base",
    "BoardRow",
    "BoardStateRow",
    "BuildReportRow",
    "JobRow",
    "LeaseRow",
    "ProvenanceRow",
    "SlotRow",
]

_TAG = String(12)
_NAME = String(64)


class Base(DeclarativeBase):
    """The declarative base every table hangs off."""


class BoardRow(Base):
    """One carrier in the rack."""

    __tablename__ = "boards"
    __table_args__ = (
        UniqueConstraint("rack", "shelf", "slot", name="uq_boards_location"),
        UniqueConstraint("adapter_serial", name="uq_boards_adapter"),
        UniqueConstraint("pdu", "outlet", name="uq_boards_outlet"),
        CheckConstraint("shelf between 1 and 12", name="ck_boards_shelf"),
        CheckConstraint("slot between 1 and 6", name="ck_boards_slot"),
        CheckConstraint("max_tck_hz > 0", name="ck_boards_tck"),
        Index("ix_boards_model", "model"),
        Index("ix_boards_device", "device"),
    )

    tag: Mapped[str] = mapped_column(_TAG, primary_key=True)
    model: Mapped[str] = mapped_column(_NAME)
    revision: Mapped[str] = mapped_column(String(8), default="a")
    part: Mapped[str] = mapped_column(String(32))
    device: Mapped[str] = mapped_column(String(16))
    flash: Mapped[str] = mapped_column(String(32))
    rack: Mapped[str] = mapped_column(_NAME)
    shelf: Mapped[int] = mapped_column(Integer)
    slot: Mapped[int] = mapped_column(Integer)
    adapter_serial: Mapped[str | None] = mapped_column(String(16), nullable=True)
    adapter_model: Mapped[str | None] = mapped_column(_NAME, nullable=True)
    adapter_port: Mapped[str] = mapped_column(_NAME, default="")
    pdu: Mapped[str | None] = mapped_column(_NAME, nullable=True)
    outlet: Mapped[int | None] = mapped_column(Integer, nullable=True)
    vadj_millivolts: Mapped[int] = mapped_column(Integer, default=1800)
    header_voltage_mv: Mapped[int] = mapped_column(Integer, default=1800)
    max_tck_hz: Mapped[int] = mapped_column(Integer, default=30_000_000)
    notes: Mapped[str] = mapped_column(String(240), default="")

    slots: Mapped[list[SlotRow]] = relationship(
        back_populates="board",
        cascade="all, delete-orphan",
        order_by="SlotRow.name",
    )
    state: Mapped[BoardStateRow | None] = relationship(
        back_populates="board",
        cascade="all, delete-orphan",
        uselist=False,
    )


class SlotRow(Base):
    """One connector on a carrier and whatever is in it."""

    __tablename__ = "board_slots"
    __table_args__ = (
        UniqueConstraint("board_tag", "name", name="uq_slots_name"),
        Index("ix_slots_fitted", "fitted"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    board_tag: Mapped[str] = mapped_column(_TAG, ForeignKey("boards.tag", ondelete="CASCADE"))
    name: Mapped[str] = mapped_column(_NAME)
    connector: Mapped[str] = mapped_column(_NAME)
    fitted: Mapped[str | None] = mapped_column(_NAME, nullable=True)

    board: Mapped[BoardRow] = relationship(back_populates="slots")


class BoardStateRow(Base):
    """Where a board is now, kept beside it rather than on it."""

    __tablename__ = "board_states"
    __table_args__ = (
        Index("ix_board_states_state", "state"),
        CheckConstraint("length(state) > 0", name="ck_board_states_state"),
    )

    board_tag: Mapped[str] = mapped_column(
        _TAG,
        ForeignKey("boards.tag", ondelete="CASCADE"),
        primary_key=True,
    )
    state: Mapped[str] = mapped_column(String(16))
    since: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    reason: Mapped[str] = mapped_column(String(240), default="")
    actor: Mapped[str] = mapped_column(_NAME, default="")
    is_available: Mapped[bool] = mapped_column(Boolean, default=True)

    board: Mapped[BoardRow] = relationship(back_populates="state")


class LeaseRow(Base):
    """One hold on one board, live or closed.

    The partial index is the interesting line. One board can have any number
    of closed leases and at most one open one, and saying that in the schema
    means two writers racing to take the same board produce an error rather
    than two holders.
    """

    __tablename__ = "leases"
    __table_args__ = (
        Index(
            "uq_leases_active_board",
            "board_tag",
            unique=True,
            sqlite_where=text("released_at IS NULL"),
            postgresql_where=text("released_at IS NULL"),
        ),
        Index("ix_leases_holder", "holder"),
        Index("ix_leases_taken_at", "taken_at"),
        CheckConstraint("priority between 1 and 9", name="ck_leases_priority"),
        CheckConstraint("expires_at > taken_at", name="ck_leases_window"),
    )

    id: Mapped[str] = mapped_column(String(24), primary_key=True)
    board_tag: Mapped[str] = mapped_column(_TAG, ForeignKey("boards.tag", ondelete="RESTRICT"))
    holder: Mapped[str] = mapped_column(_NAME)
    purpose: Mapped[str] = mapped_column(String(240))
    taken_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    priority: Mapped[int] = mapped_column(Integer, default=5)
    renewals: Mapped[int] = mapped_column(Integer, default=0)
    preemptible: Mapped[bool] = mapped_column(Boolean, default=True)
    released_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    release_reason: Mapped[str] = mapped_column(String(120), default="")


class JobRow(Base):
    """One programming job, from queued to whatever became of it."""

    __tablename__ = "jobs"
    __table_args__ = (
        Index("ix_jobs_board_state", "board_tag", "state"),
        Index("ix_jobs_digest", "digest"),
        Index("ix_jobs_created_at", "created_at"),
        CheckConstraint("attempts >= 0", name="ck_jobs_attempts"),
        CheckConstraint("length(digest) = 64", name="ck_jobs_digest"),
    )

    id: Mapped[str] = mapped_column(String(24), primary_key=True)
    board_tag: Mapped[str] = mapped_column(_TAG, ForeignKey("boards.tag", ondelete="RESTRICT"))
    digest: Mapped[str] = mapped_column(String(64))
    mode: Mapped[str] = mapped_column(String(16))
    requested_by: Mapped[str] = mapped_column(_NAME)
    state: Mapped[str] = mapped_column(String(16), default="queued")
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    verify: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    not_before: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    error: Mapped[str] = mapped_column(String(240), default="")
    error_code: Mapped[str] = mapped_column(String(32), default="")
    clocks: Mapped[int] = mapped_column(Integer, default=0)
    lease_id: Mapped[str | None] = mapped_column(String(24), nullable=True)


class ArtifactRow(Base):
    """One stored image, addressed by what is inside it."""

    __tablename__ = "artifacts"
    __table_args__ = (
        Index("ix_artifacts_design", "design"),
        Index("ix_artifacts_channel", "channel"),
        Index("ix_artifacts_device", "device"),
        CheckConstraint("length(digest) = 64", name="ck_artifacts_digest"),
        CheckConstraint("length_bytes > 0", name="ck_artifacts_length"),
    )

    digest: Mapped[str] = mapped_column(String(64), primary_key=True)
    design: Mapped[str] = mapped_column(_NAME)
    device: Mapped[str] = mapped_column(String(16))
    length_bytes: Mapped[int] = mapped_column(Integer)
    kind: Mapped[str] = mapped_column(String(8))
    channel: Mapped[str] = mapped_column(String(16), default="dev")
    source: Mapped[str] = mapped_column(_NAME, default="")
    tool_version: Mapped[str] = mapped_column(String(32), default="")
    stored_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    tags: Mapped[str] = mapped_column(String(240), default="")

    builds: Mapped[list[ProvenanceRow]] = relationship(
        back_populates="artifact",
        cascade="all, delete-orphan",
        order_by="ProvenanceRow.built_at",
    )
    reports: Mapped[list[BuildReportRow]] = relationship(
        back_populates="artifact",
        cascade="all, delete-orphan",
    )


class ProvenanceRow(Base):
    """One build that produced an image.

    Several builds can produce the same image, so the key is the pair rather
    than the digest. That is not a hypothetical: rebuilding a tag on a
    different agent does it every time.
    """

    __tablename__ = "provenance"
    __table_args__ = (
        UniqueConstraint("digest", "build_id", name="uq_provenance_build"),
        Index("ix_provenance_revision", "source_revision"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    digest: Mapped[str] = mapped_column(
        String(64),
        ForeignKey("artifacts.digest", ondelete="CASCADE"),
    )
    design: Mapped[str] = mapped_column(_NAME)
    source_revision: Mapped[str] = mapped_column(String(40))
    build_id: Mapped[str] = mapped_column(_NAME)
    built_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    tool_version: Mapped[str] = mapped_column(String(32), default="")
    constraints_digest: Mapped[str] = mapped_column(String(64), default="")
    builder: Mapped[str] = mapped_column(_NAME, default="")
    branch: Mapped[str] = mapped_column(_NAME, default="")
    dirty: Mapped[bool] = mapped_column(Boolean, default=False)

    artifact: Mapped[ArtifactRow] = relationship(back_populates="builds")


class BuildReportRow(Base):
    """The numbers out of one build's timing and utilization reports."""

    __tablename__ = "build_reports"
    __table_args__ = (
        UniqueConstraint("digest", name="uq_build_reports_digest"),
        Index("ix_build_reports_design", "design"),
        CheckConstraint("setup_failing >= 0", name="ck_reports_setup_failing"),
        CheckConstraint("hold_failing >= 0", name="ck_reports_hold_failing"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    digest: Mapped[str] = mapped_column(
        String(64),
        ForeignKey("artifacts.digest", ondelete="CASCADE"),
    )
    design: Mapped[str] = mapped_column(_NAME)
    read_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    wns_ps: Mapped[int] = mapped_column(Integer)
    tns_ps: Mapped[int] = mapped_column(Integer)
    setup_failing: Mapped[int] = mapped_column(Integer, default=0)
    setup_total: Mapped[int] = mapped_column(Integer, default=0)
    whs_ps: Mapped[int] = mapped_column(Integer)
    ths_ps: Mapped[int] = mapped_column(Integer)
    hold_failing: Mapped[int] = mapped_column(Integer, default=0)
    hold_total: Mapped[int] = mapped_column(Integer, default=0)
    luts: Mapped[int] = mapped_column(Integer, default=0)
    registers: Mapped[int] = mapped_column(Integer, default=0)
    block_ram: Mapped[int] = mapped_column(Integer, default=0)
    dsp: Mapped[int] = mapped_column(Integer, default=0)
    is_baseline: Mapped[bool] = mapped_column(Boolean, default=False)

    artifact: Mapped[ArtifactRow] = relationship(back_populates="reports")


class ApiKeyRow(Base):
    """One credential, stored as a digest rather than as a secret.

    The secret is never written down. What is stored is a sha256 of it, so a
    copy of this table is not a way into the bench, and a lost key is issued
    again rather than looked up.
    """

    __tablename__ = "api_keys"
    __table_args__ = (
        UniqueConstraint("secret_digest", name="uq_api_keys_secret"),
        Index("ix_api_keys_role", "role"),
        CheckConstraint("length(secret_digest) = 64", name="ck_api_keys_digest"),
    )

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    label: Mapped[str] = mapped_column(_NAME)
    secret_digest: Mapped[str] = mapped_column(String(64))
    role: Mapped[str] = mapped_column(String(16))
    team: Mapped[str] = mapped_column(_NAME, default="")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    last_used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    active: Mapped[bool] = mapped_column(Boolean, default=True)
