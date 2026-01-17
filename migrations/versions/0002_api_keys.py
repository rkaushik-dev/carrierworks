"""credentials, stored as digests rather than as secrets

Revision ID: 0002
Revises: 0001
Created: 2026-01-24

The bench ran open on the lab network until now, which was fine while it was
one person's script and stopped being fine the moment the regression runners
started programming boards on their own.
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "api_keys",
        sa.Column("id", sa.String(32), primary_key=True),
        sa.Column("label", sa.String(64), nullable=False),
        sa.Column("secret_digest", sa.String(64), nullable=False),
        sa.Column("role", sa.String(16), nullable=False),
        sa.Column("team", sa.String(64), nullable=False, server_default=""),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("last_used_at", sa.DateTime(timezone=True)),
        sa.Column("active", sa.Boolean, nullable=False, server_default=sa.text("1")),
        sa.UniqueConstraint("secret_digest", name="uq_api_keys_secret"),
        sa.CheckConstraint("length(secret_digest) = 64", name="ck_api_keys_digest"),
    )
    op.create_index("ix_api_keys_role", "api_keys", ["role"])


def downgrade() -> None:
    op.drop_table("api_keys")
