"""The audit routes: who did what, most recent first."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from fastapi import APIRouter, Depends, Query

from fpga.access import ApiKey
from fpga.api.deps import unit_of_work
from fpga.api.security import needs
from fpga.audit import AuditEntry
from fpga.persistence.unit import UnitOfWork
from fpga.services import audit

router = APIRouter(prefix="/audit", tags=["audit"])


def _out(entry: AuditEntry) -> dict[str, Any]:
    return {
        "at": entry.at,
        "actor": entry.actor,
        "action": entry.action,
        "subject": entry.subject,
        "detail": entry.detail,
    }


@router.get("")
def read_trail(
    unit: UnitOfWork = Depends(unit_of_work),
    subject: str | None = Query(default=None, description="one board, image or lease"),
    actor: str | None = Query(default=None, description="one person or script"),
    action: str | None = Query(default=None, description="one kind of thing done"),
    since: datetime | None = Query(default=None, description="nothing older than this"),
    limit: int = Query(default=50, ge=1, le=500),
    _: ApiKey = Depends(needs("reader")),
) -> list[dict[str, Any]]:
    """The trail, newest first, narrowed by whatever was asked for."""
    if subject is None and actor is None and action is None and since is None:
        return [_out(entry) for entry in audit.recent(unit, limit)]
    return [
        _out(entry)
        for entry in audit.search(
            unit,
            subject=subject,
            actor=actor,
            action=action,
            since=since,
            limit=limit,
        )
    ]
