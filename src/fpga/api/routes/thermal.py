"""The thermal routes: readings in, states out, and the pass that acts.

Recording is the hot path on this service. Rack controllers post a reading per
board every twenty seconds, which is why this route takes a batch: one post per
controller per poll instead of one per board, and the whole batch lands in a
single transaction or none of it does.

The tick route is a post rather than a get because it changes things, and it is
behind the operator role for the same reason. Everything else here is a read.
"""

from __future__ import annotations

from datetime import datetime, timedelta

from fastapi import APIRouter, Body, Depends, Query

from fpga.access import ApiKey
from fpga.api.deps import get_settings, now, unit_of_work
from fpga.api.schemas import SampleIn, SampleOut, StatusOut, SupervisorActionOut
from fpga.api.security import needs
from fpga.config import BenchSettings
from fpga.persistence.unit import UnitOfWork
from fpga.services import thermal as thermal_service

router = APIRouter(tags=["thermal"])


@router.post("/thermal/samples", response_model=list[SampleOut], status_code=201)
def record_samples(
    readings: list[SampleIn] = Body(min_length=1, max_length=256),
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    _: ApiKey = Depends(needs("operator")),
) -> list[SampleOut]:
    """Store a batch of readings, all or nothing."""
    stored = [
        thermal_service.record(
            unit,
            reading.board,
            reading.at or moment,
            reading.millidegrees,
            source=reading.source,
        )
        for reading in readings
    ]
    return [SampleOut.of(sample) for sample in stored]


@router.get("/thermal/status", response_model=list[StatusOut])
def read_status(
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    settings: BenchSettings = Depends(get_settings),
    window: int = Query(default=15, ge=1, le=1440, description="minutes of readings to look at"),
    _: ApiKey = Depends(needs("reader")),
) -> list[StatusOut]:
    """Where every board stands, worst first."""
    statuses = thermal_service.status_of(
        unit,
        moment,
        settings=settings,
        window=timedelta(minutes=window),
    )
    return [StatusOut.of(status, moment) for status in statuses]


@router.get("/boards/{tag}/thermal", response_model=list[SampleOut])
def read_board_history(
    tag: str,
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    window: int = Query(default=60, ge=1, le=10080, description="minutes back to go"),
    _: ApiKey = Depends(needs("reader")),
) -> list[SampleOut]:
    """One board's readings, oldest first."""
    unit.boards.get(tag)
    samples = unit.thermal.since(moment - timedelta(minutes=window), tag)
    return [SampleOut.of(sample) for sample in samples]


@router.post("/thermal/tick", response_model=list[SupervisorActionOut])
def run_tick(
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    settings: BenchSettings = Depends(get_settings),
    window: int = Query(default=15, ge=1, le=1440, description="minutes of readings to look at"),
    _: ApiKey = Depends(needs("operator")),
) -> list[SupervisorActionOut]:
    """One supervisor pass, which can take a board out of service."""
    actions = thermal_service.tick(
        unit,
        moment,
        settings=settings,
        window=timedelta(minutes=window),
    )
    return [SupervisorActionOut.of(action) for action in actions]
