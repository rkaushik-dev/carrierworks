"""the bench as it stood when the database was introduced

Revision ID: 0001
Revises:
Created: 2025-12-15

Everything the bench had been keeping in json files up to now, in one schema.
The tables are created in dependency order and dropped in the reverse of it, so
the downgrade actually runs rather than tripping over a foreign key.
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "boards",
        sa.Column("tag", sa.String(12), primary_key=True),
        sa.Column("model", sa.String(64), nullable=False),
        sa.Column("revision", sa.String(8), nullable=False, server_default="a"),
        sa.Column("part", sa.String(32), nullable=False),
        sa.Column("device", sa.String(16), nullable=False),
        sa.Column("flash", sa.String(32), nullable=False),
        sa.Column("rack", sa.String(64), nullable=False),
        sa.Column("shelf", sa.Integer, nullable=False),
        sa.Column("slot", sa.Integer, nullable=False),
        sa.Column("adapter_serial", sa.String(16)),
        sa.Column("adapter_model", sa.String(64)),
        sa.Column("adapter_port", sa.String(64), nullable=False, server_default=""),
        sa.Column("pdu", sa.String(64)),
        sa.Column("outlet", sa.Integer),
        sa.Column("vadj_millivolts", sa.Integer, nullable=False, server_default="1800"),
        sa.Column("header_voltage_mv", sa.Integer, nullable=False, server_default="1800"),
        sa.Column("max_tck_hz", sa.Integer, nullable=False, server_default="30000000"),
        sa.Column("notes", sa.String(240), nullable=False, server_default=""),
        sa.UniqueConstraint("rack", "shelf", "slot", name="uq_boards_location"),
        sa.UniqueConstraint("adapter_serial", name="uq_boards_adapter"),
        sa.UniqueConstraint("pdu", "outlet", name="uq_boards_outlet"),
        sa.CheckConstraint("shelf between 1 and 12", name="ck_boards_shelf"),
        sa.CheckConstraint("slot between 1 and 6", name="ck_boards_slot"),
        sa.CheckConstraint("max_tck_hz > 0", name="ck_boards_tck"),
    )
    op.create_index("ix_boards_model", "boards", ["model"])
    op.create_index("ix_boards_device", "boards", ["device"])

    op.create_table(
        "board_slots",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column(
            "board_tag",
            sa.String(12),
            sa.ForeignKey("boards.tag", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("name", sa.String(64), nullable=False),
        sa.Column("connector", sa.String(64), nullable=False),
        sa.Column("fitted", sa.String(64)),
        sa.UniqueConstraint("board_tag", "name", name="uq_slots_name"),
    )
    op.create_index("ix_slots_fitted", "board_slots", ["fitted"])

    op.create_table(
        "board_states",
        sa.Column(
            "board_tag",
            sa.String(12),
            sa.ForeignKey("boards.tag", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column("state", sa.String(16), nullable=False),
        sa.Column("since", sa.DateTime(timezone=True), nullable=False),
        sa.Column("reason", sa.String(240), nullable=False, server_default=""),
        sa.Column("actor", sa.String(64), nullable=False, server_default=""),
        sa.Column("is_available", sa.Boolean, nullable=False, server_default=sa.text("1")),
        sa.CheckConstraint("length(state) > 0", name="ck_board_states_state"),
    )
    op.create_index("ix_board_states_state", "board_states", ["state"])

    op.create_table(
        "leases",
        sa.Column("id", sa.String(24), primary_key=True),
        sa.Column(
            "board_tag",
            sa.String(12),
            sa.ForeignKey("boards.tag", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("holder", sa.String(64), nullable=False),
        sa.Column("purpose", sa.String(240), nullable=False),
        sa.Column("taken_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("priority", sa.Integer, nullable=False, server_default="5"),
        sa.Column("renewals", sa.Integer, nullable=False, server_default="0"),
        sa.Column("preemptible", sa.Boolean, nullable=False, server_default=sa.text("1")),
        sa.Column("released_at", sa.DateTime(timezone=True)),
        sa.Column("release_reason", sa.String(120), nullable=False, server_default=""),
        sa.CheckConstraint("priority between 1 and 9", name="ck_leases_priority"),
        sa.CheckConstraint("expires_at > taken_at", name="ck_leases_window"),
    )
    op.create_index(
        "uq_leases_active_board",
        "leases",
        ["board_tag"],
        unique=True,
        sqlite_where=sa.text("released_at IS NULL"),
        postgresql_where=sa.text("released_at IS NULL"),
    )
    op.create_index("ix_leases_holder", "leases", ["holder"])
    op.create_index("ix_leases_taken_at", "leases", ["taken_at"])

    op.create_table(
        "jobs",
        sa.Column("id", sa.String(24), primary_key=True),
        sa.Column(
            "board_tag",
            sa.String(12),
            sa.ForeignKey("boards.tag", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("digest", sa.String(64), nullable=False),
        sa.Column("mode", sa.String(16), nullable=False),
        sa.Column("requested_by", sa.String(64), nullable=False),
        sa.Column("state", sa.String(16), nullable=False, server_default="queued"),
        sa.Column("attempts", sa.Integer, nullable=False, server_default="0"),
        sa.Column("verify", sa.Boolean, nullable=False, server_default=sa.text("1")),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True)),
        sa.Column("finished_at", sa.DateTime(timezone=True)),
        sa.Column("not_before", sa.DateTime(timezone=True)),
        sa.Column("error", sa.String(240), nullable=False, server_default=""),
        sa.Column("error_code", sa.String(32), nullable=False, server_default=""),
        sa.Column("clocks", sa.Integer, nullable=False, server_default="0"),
        sa.Column("lease_id", sa.String(24)),
        sa.CheckConstraint("attempts >= 0", name="ck_jobs_attempts"),
        sa.CheckConstraint("length(digest) = 64", name="ck_jobs_digest"),
    )
    op.create_index("ix_jobs_board_state", "jobs", ["board_tag", "state"])
    op.create_index("ix_jobs_digest", "jobs", ["digest"])
    op.create_index("ix_jobs_created_at", "jobs", ["created_at"])

    op.create_table(
        "artifacts",
        sa.Column("digest", sa.String(64), primary_key=True),
        sa.Column("design", sa.String(64), nullable=False),
        sa.Column("device", sa.String(16), nullable=False),
        sa.Column("length_bytes", sa.Integer, nullable=False),
        sa.Column("kind", sa.String(8), nullable=False),
        sa.Column("channel", sa.String(16), nullable=False, server_default="dev"),
        sa.Column("source", sa.String(64), nullable=False, server_default=""),
        sa.Column("tool_version", sa.String(32), nullable=False, server_default=""),
        sa.Column("stored_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("tags", sa.String(240), nullable=False, server_default=""),
        sa.CheckConstraint("length(digest) = 64", name="ck_artifacts_digest"),
        sa.CheckConstraint("length_bytes > 0", name="ck_artifacts_length"),
    )
    op.create_index("ix_artifacts_design", "artifacts", ["design"])
    op.create_index("ix_artifacts_channel", "artifacts", ["channel"])
    op.create_index("ix_artifacts_device", "artifacts", ["device"])

    op.create_table(
        "provenance",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column(
            "digest",
            sa.String(64),
            sa.ForeignKey("artifacts.digest", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("design", sa.String(64), nullable=False),
        sa.Column("source_revision", sa.String(40), nullable=False),
        sa.Column("build_id", sa.String(64), nullable=False),
        sa.Column("built_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("tool_version", sa.String(32), nullable=False, server_default=""),
        sa.Column("constraints_digest", sa.String(64), nullable=False, server_default=""),
        sa.Column("builder", sa.String(64), nullable=False, server_default=""),
        sa.Column("branch", sa.String(64), nullable=False, server_default=""),
        sa.Column("dirty", sa.Boolean, nullable=False, server_default=sa.text("0")),
        sa.UniqueConstraint("digest", "build_id", name="uq_provenance_build"),
    )
    op.create_index("ix_provenance_revision", "provenance", ["source_revision"])

    op.create_table(
        "build_reports",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column(
            "digest",
            sa.String(64),
            sa.ForeignKey("artifacts.digest", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("design", sa.String(64), nullable=False),
        sa.Column("read_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("wns_ps", sa.Integer, nullable=False),
        sa.Column("tns_ps", sa.Integer, nullable=False),
        sa.Column("setup_failing", sa.Integer, nullable=False, server_default="0"),
        sa.Column("setup_total", sa.Integer, nullable=False, server_default="0"),
        sa.Column("whs_ps", sa.Integer, nullable=False),
        sa.Column("ths_ps", sa.Integer, nullable=False),
        sa.Column("hold_failing", sa.Integer, nullable=False, server_default="0"),
        sa.Column("hold_total", sa.Integer, nullable=False, server_default="0"),
        sa.Column("luts", sa.Integer, nullable=False, server_default="0"),
        sa.Column("registers", sa.Integer, nullable=False, server_default="0"),
        sa.Column("block_ram", sa.Integer, nullable=False, server_default="0"),
        sa.Column("dsp", sa.Integer, nullable=False, server_default="0"),
        sa.Column("is_baseline", sa.Boolean, nullable=False, server_default=sa.text("0")),
        sa.UniqueConstraint("digest", name="uq_build_reports_digest"),
        sa.CheckConstraint("setup_failing >= 0", name="ck_reports_setup_failing"),
        sa.CheckConstraint("hold_failing >= 0", name="ck_reports_hold_failing"),
    )
    op.create_index("ix_build_reports_design", "build_reports", ["design"])


def downgrade() -> None:
    op.drop_table("build_reports")
    op.drop_table("provenance")
    op.drop_table("artifacts")
    op.drop_table("jobs")
    op.drop_table("leases")
    op.drop_table("board_states")
    op.drop_table("board_slots")
    op.drop_table("boards")
