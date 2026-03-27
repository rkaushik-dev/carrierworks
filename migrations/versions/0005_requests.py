"""a waiting list for boards nobody has free

Revision ID: 0005
Revises: 0004
Created: 2026-03-24

The queue used to live in the scheduler's memory, which meant a restart lost
everybody's place in line and there was no way to answer how long a team had
been waiting. Both of those turned out to matter more than the queue being
fast.
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "requests",
        sa.Column("id", sa.String(16), primary_key=True),
        sa.Column("holder", sa.String(64), nullable=False),
        sa.Column("purpose", sa.String(120), nullable=False),
        sa.Column("requirements", sa.String(500), nullable=False, server_default=""),
        sa.Column("submitted_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("priority", sa.Integer, nullable=False, server_default="5"),
        sa.Column("minutes", sa.Integer, nullable=False, server_default="60"),
        sa.Column("preemptible", sa.Boolean, nullable=False, server_default=sa.text("1")),
        sa.Column("needs_hands", sa.Boolean, nullable=False, server_default=sa.text("0")),
        sa.Column("cancelled_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("satisfied_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("assigned_board", sa.String(12), nullable=False, server_default=""),
        sa.CheckConstraint("priority between 1 and 9", name="ck_requests_priority"),
        sa.CheckConstraint("minutes > 0", name="ck_requests_minutes"),
    )
    op.create_index("ix_requests_open", "requests", ["satisfied_at", "cancelled_at"])
    op.create_index("ix_requests_holder", "requests", ["holder"])


def downgrade() -> None:
    op.drop_table("requests")
