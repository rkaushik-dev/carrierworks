"""The lease routes: who has what, and for how much longer."""

from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Body, Depends, Query

from fpga.access import ApiKey
from fpga.api.deps import get_settings, now, unit_of_work
from fpga.api.schemas import LeaseIn, LeaseOut
from fpga.api.security import needs
from fpga.config import BenchSettings
from fpga.persistence.unit import UnitOfWork
from fpga.services import leasing

router = APIRouter(tags=["leases"])


@router.get("/leases", response_model=list[LeaseOut])
def list_leases(
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    holder: str | None = Query(default=None, description="one team rather than all of them"),
    _: ApiKey = Depends(needs("reader")),
) -> list[LeaseOut]:
    """Every lease being held right now."""
    if holder is not None:
        return [LeaseOut.of(lease) for lease in unit.leases.held_by(holder, moment)]
    return [LeaseOut.of(lease) for lease in unit.leases.active(moment)]


@router.get("/leases/{lease_id}", response_model=LeaseOut)
def read_lease(
    lease_id: str,
    unit: UnitOfWork = Depends(unit_of_work),
    _: ApiKey = Depends(needs("reader")),
) -> LeaseOut:
    """One lease, live or closed."""
    return LeaseOut.of(unit.leases.get(lease_id))


@router.get("/boards/{tag}/lease", response_model=LeaseOut | None)
def read_board_lease(
    tag: str,
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    _: ApiKey = Depends(needs("reader")),
) -> LeaseOut | None:
    """Whatever holds a board, or nothing when it is free."""
    unit.boards.get(tag)
    held = unit.leases.for_board(tag, moment)
    return None if held is None else LeaseOut.of(held)


@router.post("/boards/{tag}/lease", response_model=LeaseOut, status_code=201)
def take_board(
    tag: str,
    asked: LeaseIn,
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    settings: BenchSettings = Depends(get_settings),
    _: ApiKey = Depends(needs("operator")),
) -> LeaseOut:
    """Take a board, if it is free and in service."""
    lease = leasing.acquire(
        unit,
        tag,
        asked.holder,
        asked.purpose,
        moment,
        minutes=asked.minutes,
        priority=asked.priority,
        preemptible=asked.preemptible,
        settings=settings,
    )
    return LeaseOut.of(lease)


@router.post("/leases/{lease_id}/release", response_model=LeaseOut)
def release_lease(
    lease_id: str,
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    reason: str = Query(default="released", description="why it is being given back"),
    _: ApiKey = Depends(needs("operator")),
) -> LeaseOut:
    """Give a board back."""
    return LeaseOut.of(leasing.release(unit, lease_id, moment, reason))


@router.post("/leases/{lease_id}/renew", response_model=LeaseOut)
def renew_lease(
    lease_id: str,
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    settings: BenchSettings = Depends(get_settings),
    minutes: int = Body(default=60, embed=True, description="how much longer"),
    _: ApiKey = Depends(needs("operator")),
) -> LeaseOut:
    """Keep a board a bit longer, up to the ceiling."""
    return LeaseOut.of(leasing.renew(unit, lease_id, moment, minutes, settings=settings))


@router.post("/leases/sweep", response_model=list[LeaseOut])
def sweep(
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
) -> list[LeaseOut]:
    """Close the leases that have run out and free their boards."""
    return [LeaseOut.of(lease) for lease in leasing.sweep_expired(unit, moment)]
