"""an append only record of who did what

Revision ID: 0003
Revises: 0002
Created: 2026-02-02

Added after the morning nobody could say who had power cycled the whole of
rack two overnight. The answer turned out to be a script, which is the answer
this table exists to give in a minute rather than in a day.
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "audit",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column("at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("actor", sa.String(64), nullable=False),
        sa.Column("action", sa.String(32), nullable=False),
        sa.Column("subject", sa.String(64), nullable=False, server_default=""),
        sa.Column("detail", sa.String(240), nullable=False, server_default=""),
        sa.Column("key_id", sa.String(32), nullable=False, server_default=""),
        sa.CheckConstraint("length(action) > 0", name="ck_audit_action"),
    )
    op.create_index("ix_audit_at", "audit", ["at"])
    op.create_index("ix_audit_subject", "audit", ["subject"])
    op.create_index("ix_audit_actor", "audit", ["actor"])


def downgrade() -> None:
    op.drop_table("audit")
