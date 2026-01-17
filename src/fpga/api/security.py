"""Turning a header into a key, and a role into a dependency.

A bench with no keys issued is open. That sounds alarming written down and it
is the only behaviour that works: the service starts empty, somebody has to be
able to issue the first key, and every lab that runs this on a laptop for an
afternoon would otherwise have to invent a credential first. The moment a
single key exists, everything asks for one.

Roles are attached to routes rather than checked inside them, so what a route
needs is visible in its signature and a route that forgot to ask stands out.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import datetime

from fastapi import Depends, Request

from fpga.access import ApiKey, require_role
from fpga.api.deps import get_settings, now, unit_of_work
from fpga.config import BenchSettings
from fpga.persistence.unit import UnitOfWork
from fpga.services import security

__all__ = [
    "OPEN_BENCH",
    "current_key",
    "needs",
]

# The key a bench with no keys issued runs as.
OPEN_BENCH = ApiKey(id="open", label="open bench", role="admin")


def current_key(
    request: Request,
    unit: UnitOfWork = Depends(unit_of_work),
    settings: BenchSettings = Depends(get_settings),
    moment: datetime = Depends(now),
) -> ApiKey:
    """Whoever is calling, or the open bench key when none have been issued."""
    if not unit.api_keys.all():
        return OPEN_BENCH
    presented = request.headers.get(settings.api_key_header)
    return security.authenticate(unit, presented, moment)


def needs(role: str) -> Callable[[ApiKey], ApiKey]:
    """A dependency that refuses anything without the role asked for."""

    def dependency(key: ApiKey = Depends(current_key)) -> ApiKey:
        return require_role(key, role)

    return dependency
