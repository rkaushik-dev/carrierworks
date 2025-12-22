"""The application factory, and the one shape every error comes back in.

Every failure the bench raises on purpose is a BenchError with a stable code, so
the handlers here are a mapping from those codes to status and nothing else. A
route never builds an error response; it raises the same exception it would
raise in the command line tool and this turns it into json.

The status mapping is short and worth stating. Not found is a 404, a conflict
is a 409 because somebody else got there first and retrying might work, a
validation failure is a 422 because the request was understood and refused, a
configuration error is a 500 because the bench is wrong rather than the caller,
and anything transient is a 503 with the retryable flag set so a client knows
it can come back.
"""

from __future__ import annotations

from typing import Any

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from fpga.config import BenchSettings
from fpga.errors import (
    BenchError,
    ConfigurationError,
    Conflict,
    DeviceMismatch,
    NotFound,
    TransientBenchError,
    ValidationError,
)
from fpga.persistence.unit import BenchDatabase

__all__ = [
    "build_app",
    "status_for",
]


def status_for(error: BenchError) -> int:
    """Which status an error comes back as."""
    if isinstance(error, NotFound):
        return 404
    if isinstance(error, Conflict):
        return 409
    if isinstance(error, DeviceMismatch):
        return 422
    if isinstance(error, ValidationError):
        return 422
    if isinstance(error, TransientBenchError):
        return 503
    if isinstance(error, ConfigurationError):
        return 500
    return 400


def build_app(
    database: BenchDatabase | None = None,
    settings: BenchSettings | None = None,
) -> FastAPI:
    """The application, with its database and settings hung off its state."""
    live = settings or BenchSettings()
    app = FastAPI(
        title="fpga bench",
        version="0.1.0",
        summary="The board farm the prototyping lab runs on",
    )
    app.state.settings = live
    app.state.database = database or BenchDatabase(live)

    @app.exception_handler(BenchError)
    async def _bench_error(request: Request, error: BenchError) -> JSONResponse:
        return JSONResponse(status_code=status_for(error), content={"error": error.as_dict()})

    @app.get("/health", tags=["bench"])
    def health() -> dict[str, Any]:
        """Whether the service is up and which site it thinks it is."""
        return {"status": "ok", "site": live.site, "version": app.version}

    @app.get("/settings", tags=["bench"])
    def read_settings() -> dict[str, Any]:
        """The settings in force, with nothing secret in them to redact."""
        return {
            "site": live.site,
            "default_lease_minutes": live.default_lease_minutes,
            "max_lease_minutes": live.max_lease_minutes,
            "program_attempts": live.program_attempts,
            "verify_after_program": live.verify_after_program,
            "require_timing_closure": live.require_timing_closure,
            "staffed_hours": live.staffed_hours,
        }

    return app
