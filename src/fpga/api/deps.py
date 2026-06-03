"""What the routes are handed, and where it comes from.

Four things: a unit of work, the settings, the time, and a page.

The clock is the one that matters for testing, because a route that calls it
itself cannot be tested at a moment of anybody's choosing, and half the rules
in this bench are about when something happened. So it is a dependency with a
default that reads the real one, and a test overrides it the way it would
override a database.

The page is here rather than repeated in every listing route because it was
repeated in every listing route, with three different maximums and one route
that had no limit at all and would happily return a year of thermal readings.
"""

from __future__ import annotations

from collections.abc import Iterator, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import TypeVar

from fastapi import Depends, Query, Request

from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase, UnitOfWork

__all__ = [
    "Page",
    "get_database",
    "get_settings",
    "now",
    "page",
    "unit_of_work",
]

Item = TypeVar("Item")

# Nothing on this bench has more rows than this that anybody wants in one go.
MAX_PAGE = 500


def get_database(request: Request) -> BenchDatabase:
    database: BenchDatabase = request.app.state.database
    return database


def get_settings(request: Request) -> BenchSettings:
    settings: BenchSettings = request.app.state.settings
    return settings


def now() -> datetime:
    """The current moment, in utc, as one overridable dependency."""
    return datetime.now(UTC)


def unit_of_work(
    database: BenchDatabase = Depends(get_database),
) -> Iterator[UnitOfWork]:
    """A unit of work per request, committed unless the route raised."""
    unit = database.unit()
    with unit:
        yield unit


@dataclass(frozen=True, slots=True)
class Page:
    """How much of a listing to return, and from where."""

    limit: int = 50
    offset: int = 0

    def slice(self, items: Sequence[Item]) -> list[Item]:
        """The window of a listing this page asks for.

        Applied after the query rather than inside it. Every listing on this
        bench is at most a few thousand rows, and paging in the database would
        mean every repository method growing two arguments it does not
        otherwise need.
        """
        return list(items[self.offset : self.offset + self.limit])


def page(
    limit: int = Query(default=50, ge=1, le=MAX_PAGE, description="how many rows at most"),
    offset: int = Query(default=0, ge=0, description="how many rows to skip first"),
) -> Page:
    """The page a listing route was asked for."""
    return Page(limit=limit, offset=offset)
