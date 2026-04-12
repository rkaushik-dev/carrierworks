"""The status routes: the roll up, and the readiness probe under it.

Two different questions get asked of a service like this and they have
different answers. Health is whether the process is up, which the app answers
without touching anything. Readiness is whether the bench behind it is usable,
which needs the database and is the one that should take a load balancer out
of rotation.

The roll up itself is a read for anybody with a key, because it is the page on
the wall in the lab and everybody in the lab can see that wall.
"""

from __future__ import annotations

from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, Query, Response

from fpga.access import ApiKey
from fpga.api.deps import get_settings, now, unit_of_work
from fpga.api.schemas import OverviewOut
from fpga.api.security import needs
from fpga.config import BenchSettings
from fpga.errors import BenchError
from fpga.persistence.unit import UnitOfWork
from fpga.services import overview as overview_service

router = APIRouter(tags=["bench"])


@router.get("/status", response_model=OverviewOut)
def read_status(
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    settings: BenchSettings = Depends(get_settings),
    expiring_within: int = Query(
        default=15,
        ge=1,
        le=1440,
        description="minutes ahead to count leases as expiring",
    ),
    _: ApiKey = Depends(needs("reader")),
) -> OverviewOut:
    """What the whole bench is doing right now."""
    return OverviewOut.of(
        overview_service.overview(
            unit,
            moment,
            settings=settings,
            expiring_within=timedelta(minutes=expiring_within),
        )
    )


@router.get("/ready")
def read_ready(
    response: Response,
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    settings: BenchSettings = Depends(get_settings),
) -> dict[str, object]:
    """Whether this instance can serve the bench, database and all.

    Answers with a body either way rather than raising, because a probe that
    gets a five hundred and a stack trace tells whoever is looking at it less
    than one that says which check failed.
    """
    try:
        state = overview_service.overview(unit, moment, settings=settings)
    except BenchError as failure:
        response.status_code = 503
        return {"ready": False, "reason": failure.message, "code": failure.code}
    return {
        "ready": True,
        "site": state.site,
        "boards": state.total_boards,
        "available": state.available,
        "healthy": state.healthy,
    }
