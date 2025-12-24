"""What the routes are handed, and where it comes from.

Three things: a unit of work, the settings, and the time. The first two are
obvious. The third is the one that matters for testing, because a route that
calls the clock itself cannot be tested at a moment of anybody's choosing, and
half the rules in this bench are about when something happened.

So the clock is a dependency with a default that reads the real one, and a test
overrides it the same way it would override a database.
"""

from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, datetime

from fastapi import Depends, Request

from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase, UnitOfWork

__all__ = [
    "get_database",
    "get_settings",
    "now",
    "unit_of_work",
]


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
