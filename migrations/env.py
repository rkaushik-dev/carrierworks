"""Alembic environment for the bench database.

The url comes from the bench settings rather than from the ini file, so an
upgrade cannot be pointed at a different database than the service is using by
editing the wrong line.

Batch mode is on for sqlite. Sqlite cannot alter a column in place, and without
batch mode every migration that touches one fails on the machine most of this
lab runs on.
"""

from __future__ import annotations

from alembic import context
from sqlalchemy import engine_from_config, pool

from fpga.config import BenchSettings
from fpga.persistence.models import Base

config = context.config
target_metadata = Base.metadata


def _url() -> str:
    return config.get_main_option("sqlalchemy.url") or BenchSettings.from_env().database_url


def run_migrations_offline() -> None:
    """Emit sql without connecting, which is what a dba review wants."""
    context.configure(
        url=_url(),
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        render_as_batch=True,
    )
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    """Connect and run the migrations."""
    section = config.get_section(config.config_ini_section) or {}
    section["sqlalchemy.url"] = _url()
    connectable = engine_from_config(section, prefix="sqlalchemy.", poolclass=pool.NullPool)
    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
            render_as_batch=connection.dialect.name == "sqlite",
        )
        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
