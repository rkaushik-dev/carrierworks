"""Issuing keys, checking them, and turning them off.

The rules about roles live in the access module, where nothing needs a
database to reason about them. What is here is the part that does need one:
finding the key a secret belongs to, recording that it was used, and revoking
it without losing the record that it existed.
"""

from __future__ import annotations

import secrets
from datetime import datetime

from fpga.access import ApiKey, hash_secret
from fpga.errors import Forbidden, Unauthorized
from fpga.persistence.unit import UnitOfWork

__all__ = [
    "authenticate",
    "issue",
    "keys",
    "revoke",
]


def issue(
    unit: UnitOfWork,
    label: str,
    role: str,
    now: datetime,
    *,
    team: str = "",
    secret: str | None = None,
) -> tuple[ApiKey, str]:
    """Make a key, handing back the secret exactly once.

    The secret is returned rather than stored, and this is the only moment it
    exists anywhere the caller can see. Losing it means issuing another key.
    """
    plain = secret or secrets.token_urlsafe(24)
    key = ApiKey(id=secrets.token_hex(8), label=label, role=role, team=team)
    unit.api_keys.add(key, hash_secret(plain), now)
    return (key, plain)


def authenticate(unit: UnitOfWork, presented: str | None, now: datetime) -> ApiKey:
    """Turn a presented secret into a key, refusing anything that is not one."""
    if not presented or not presented.strip():
        raise Unauthorized("no key was presented")
    key = unit.api_keys.by_digest(hash_secret(presented))
    if key is None:
        raise Unauthorized("that key is not one of ours")
    if not key.active:
        raise Forbidden("that key has been revoked", holds=key.role)
    unit.api_keys.touch(key.id, now)
    return key


def revoke(unit: UnitOfWork, key_id: str) -> ApiKey:
    """Turn a key off without deleting the record of it having existed."""
    return unit.api_keys.deactivate(key_id)


def keys(unit: UnitOfWork) -> tuple[ApiKey, ...]:
    return unit.api_keys.all()
