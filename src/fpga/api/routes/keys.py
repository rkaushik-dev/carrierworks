"""The key routes: issuing credentials and turning them off."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from fastapi import APIRouter, Body, Depends

from fpga.access import ApiKey
from fpga.api.deps import now, unit_of_work
from fpga.api.security import needs
from fpga.persistence.unit import UnitOfWork
from fpga.services import security

router = APIRouter(prefix="/keys", tags=["keys"])


def _out(key: ApiKey) -> dict[str, Any]:
    return {
        "id": key.id,
        "label": key.label,
        "role": key.role,
        "team": key.team,
        "active": key.active,
    }


@router.get("")
def list_keys(
    unit: UnitOfWork = Depends(unit_of_work),
    _: ApiKey = Depends(needs("admin")),
) -> list[dict[str, Any]]:
    """Every key, live and revoked, without a secret among them."""
    return [_out(key) for key in security.keys(unit)]


@router.post("", status_code=201)
def issue_key(
    label: str = Body(embed=True),
    role: str = Body(embed=True),
    team: str = Body(default="", embed=True),
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    _: ApiKey = Depends(needs("admin")),
) -> dict[str, Any]:
    """Issue a key. The secret comes back once and is never stored."""
    key, secret = security.issue(unit, label, role, moment, team=team)
    return {**_out(key), "secret": secret}


@router.post("/{key_id}/revoke")
def revoke_key(
    key_id: str,
    unit: UnitOfWork = Depends(unit_of_work),
    _: ApiKey = Depends(needs("admin")),
) -> dict[str, Any]:
    """Turn a key off, keeping the record that it existed."""
    return _out(security.revoke(unit, key_id))


@router.get("/mine")
def read_mine(key: ApiKey = Depends(needs("reader"))) -> dict[str, Any]:
    """Whoever this key is, which is how a runner checks its own role."""
    return _out(key)
