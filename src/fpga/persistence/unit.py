"""One transaction, with every repository hanging off it.

A programming job touches the board, its state, its lease and the job row, and
those four either all land or none of them do. The unit of work is what makes
that sentence true without every service knowing about sessions.

It is deliberately not clever. Enter it, use the repositories on it, leave it,
and it commits unless something went wrong on the way out. The one extra is
that a unit which was rolled back refuses to be used again, because the
alternative is a service quietly writing into a dead session and wondering
where its rows went.
"""

from __future__ import annotations

from types import TracebackType

from sqlalchemy import Engine
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session, sessionmaker

from fpga.config import BenchSettings
from fpga.errors import Conflict, ValidationError
from fpga.persistence.engine import build_engine, create_schema, session_factory
from fpga.persistence.repositories import (
    ArtifactRepository,
    BoardRepository,
    JobRepository,
    LeaseRepository,
)

__all__ = [
    "BenchDatabase",
    "UnitOfWork",
]


def _reason(clash: IntegrityError) -> str:
    """The constraint name out of a driver message, for the error details."""
    text = str(clash.orig) if clash.orig is not None else str(clash)
    return text.strip().splitlines()[0][:160]


class UnitOfWork:
    """One session, one transaction, four repositories."""

    def __init__(self, factory: sessionmaker[Session]) -> None:
        self._factory = factory
        self._session: Session | None = None
        self.committed = False
        self.rolled_back = False

    @property
    def session(self) -> Session:
        if self._session is None:
            raise ValidationError("this unit of work is not open", field="session")
        return self._session

    @property
    def boards(self) -> BoardRepository:
        return BoardRepository(self.session)

    @property
    def leases(self) -> LeaseRepository:
        return LeaseRepository(self.session)

    @property
    def jobs(self) -> JobRepository:
        return JobRepository(self.session)

    @property
    def artifacts(self) -> ArtifactRepository:
        return ArtifactRepository(self.session)

    def __enter__(self) -> UnitOfWork:
        self._session = self._factory()
        self.committed = False
        self.rolled_back = False
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        try:
            if exc is not None:
                self.rollback()
            elif not self.committed and not self.rolled_back:
                self.commit()
        finally:
            if self._session is not None:
                self._session.close()
            self._session = None

    def commit(self) -> None:
        """Commit, turning a constraint violation into a conflict.

        The database is the last line of defence for the invariants the bench
        rests on, and when it fires the caller has raced somebody else rather
        than asked for something silly. That is a conflict, and saying so here
        keeps sqlalchemy out of every layer above this one.
        """
        try:
            self.session.commit()
        except IntegrityError as clash:
            self.rollback()
            raise Conflict("that write collided with another", detail=_reason(clash)) from clash
        self.committed = True

    def rollback(self) -> None:
        self.session.rollback()
        self.rolled_back = True

    def flush(self) -> None:
        """Push the writes out without ending the transaction.

        Needed where a later step reads something an earlier one wrote, which
        the board slot rebuild does.
        """
        self.session.flush()


class BenchDatabase:
    """The database as one object: engine, schema and units of work."""

    def __init__(self, settings: BenchSettings | None = None, *, engine: Engine | None = None):
        self.settings = settings or BenchSettings()
        self.engine = engine or build_engine(self.settings)
        self._factory = session_factory(self.engine)

    @classmethod
    def in_memory(cls) -> BenchDatabase:
        """A database that exists for as long as this object does.

        Used by the tests and by the dry run mode of the command line tool,
        which builds a whole bench, answers a question and throws it away.
        """
        settings = BenchSettings(database_url="sqlite+pysqlite:///:memory:")
        database = cls(settings)
        database.create_schema()
        return database

    def create_schema(self) -> None:
        create_schema(self.engine)

    def unit(self) -> UnitOfWork:
        return UnitOfWork(self._factory)

    def dispose(self) -> None:
        self.engine.dispose()
