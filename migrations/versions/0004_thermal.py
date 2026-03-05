"""keep the temperature readings instead of throwing them away

Revision ID: 0004
Revises: 0003
Created: 2026-02-24

The supervisor has always held its samples in memory, which is fine until it
restarts in the middle of the night and every board reads as unknown for the
next five minutes. Keeping them means a restart picks the window back up, and
it means somebody can ask what the rack was doing at the time of a failure
rather than at the time of the question.
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "thermal_samples",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column(
            "board",
            sa.String(12),
            sa.ForeignKey("boards.tag", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("millidegrees", sa.Integer, nullable=False),
        sa.Column("source", sa.String(16), nullable=False, server_default="sysmon"),
        sa.CheckConstraint("millidegrees between -60000 and 200000", name="ck_thermal_range"),
    )
    op.create_index("ix_thermal_board_at", "thermal_samples", ["board", "at"])


def downgrade() -> None:
    op.drop_table("thermal_samples")
