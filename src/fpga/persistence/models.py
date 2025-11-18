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
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

__all__ = [
    "Base",
    "BoardRow",
    "BoardStateRow",
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
