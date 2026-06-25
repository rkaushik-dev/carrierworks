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

There is a third listener and it is not about sqlite at all. A database that
has never been migrated answers every query with an operational error, and
that error escapes as a driver traceback in front of whoever ran the command.
It is the single most likely thing to go wrong on a fresh site, so it is
translated once here rather than caught in every caller.
"""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

from sqlalchemy import Engine, create_engine, event
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from fpga.config import BenchSettings
from fpga.errors import ConfigurationError
from fpga.persistence.models import Base

__all__ = [
    "build_engine",
    "create_schema",
    "session_factory",
    "session_scope",
    "unusable_database",
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

    @event.listens_for(engine, "handle_error")
    def _on_error(context: Any) -> None:
        raised = context.sqlalchemy_exception
        if isinstance(raised, OperationalError):
            raise unusable_database(raised)

    return engine


def unusable_database(failure: OperationalError) -> ConfigurationError:
    """The bench error for a database that cannot answer a query at all.

    Two things reach here and they want different sentences. A database whose
    tables are missing has not been migrated, and saying so is the whole
    answer. Anything else is a file that cannot be opened, a server that is
    not there, or a lock nobody released, and the driver's own first line says
    more about which than the bench can.
    """
    detail = str(failure.orig or failure).strip().splitlines()[0]
    if "no such table" in detail or "does not exist" in detail:
        return ConfigurationError(
            "that database has no schema yet, run alembic upgrade head",
            setting="database_url",
            detail=detail,
        )
    return ConfigurationError(
        "that database could not be used",
        setting="database_url",
        detail=detail,
    )


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
