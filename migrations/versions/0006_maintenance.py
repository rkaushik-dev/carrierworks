"""the log of who worked on which board and when

Revision ID: 0006
Revises: 0005
Created: 2026-04-07

Calibration is the reason this exists. A capture off a board whose reference
has not been checked in fourteen months is not evidence of anything, and until
now the only record of when that check happened was a sticker on the carrier.
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "maintenance",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column(
            "board",
            sa.String(12),
            sa.ForeignKey("boards.tag", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("kind", sa.String(24), nullable=False),
        sa.Column("at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("by", sa.String(64), nullable=False),
        sa.Column("notes", sa.String(240), nullable=False, server_default=""),
        sa.Column("faults_cleared", sa.Integer, nullable=False, server_default="0"),
        sa.CheckConstraint("faults_cleared >= 0", name="ck_maintenance_faults"),
    )
    op.create_index("ix_maintenance_board_at", "maintenance", ["board", "at"])
    op.create_index("ix_maintenance_kind", "maintenance", ["kind"])


def downgrade() -> None:
    op.drop_table("maintenance")
