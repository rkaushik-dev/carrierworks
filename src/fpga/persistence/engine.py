"""Getting a connection, and the two settings sqlite needs to behave.

Foreign keys are off by default in sqlite. Every cascade and every reference in
the schema is decoration until the pragma is set, and it has to be set on every
connection rather than once, which is why it is a listener rather than a line
in the setup.

Write ahead logging is the other one. The bench has a poller writing samples
while somebody runs a report, and without it the report blocks the poller for
as long as it takes to run.

Neither applies to postgres, and neither does any harm there, so the listener
checks the dialect rather than the configuration.
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

from sqlalchemy import Engine, create_engine, event
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from fpga.config import BenchSettings
from fpga.persistence.models import Base

__all__ = [
    "build_engine",
    "create_schema",
    "session_factory",
    "session_scope",
]


def build_engine(settings: BenchSettings | None = None, *, echo: bool = False) -> Engine:
    """An engine for the configured database, with sqlite made usable.

    An in memory database needs the static pool. Without it every session
    opens its own connection, and in sqlite a new connection to :memory: is a
    new empty database, so the schema created at startup is simply not there
    when the first query runs.
    """
    live = settings or BenchSettings()
    options: dict[str, object] = {"echo": echo, "future": True}
    if ":memory:" in live.database_url:
        options["poolclass"] = StaticPool
        options["connect_args"] = {"check_same_thread": False}
    engine = create_engine(live.database_url, **options)

    @event.listens_for(engine, "connect")
    def _on_connect(connection: Any, record: Any) -> None:  # pragma: no cover - driver callback
        if engine.dialect.name != "sqlite":
            return
        cursor = connection.cursor()
        cursor.execute("PRAGMA foreign_keys=ON")
        cursor.execute("PRAGMA journal_mode=WAL")
        cursor.close()

    return engine


def create_schema(engine: Engine) -> None:
    """Build every table, which is what the tests and a fresh site do."""
    Base.metadata.create_all(engine)


def session_factory(engine: Engine) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, expire_on_commit=False, future=True)


@contextmanager
def session_scope(factory: sessionmaker[Session]) -> Iterator[Session]:
    """A session that commits on the way out and rolls back on the way up."""
    session = factory()
    try:
        yield session
        session.commit()
    except Exception:
        session.rollback()
        raise
    finally:
        session.close()
