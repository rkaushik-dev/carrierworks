"""Who is allowed to do what, as three roles and one hash.

Three roles, and they are about consequence rather than about seniority. A
reader can look at anything, because the bench holds nothing secret. An
operator can take boards and program them, which is what the regression
runners and everybody in the lab needs. An admin can change what the bench
believes: adding boards, promoting an image to golden, and issuing keys.

The secret is never kept. A key is issued once, its digest is stored, and a
lost key is replaced rather than recovered. That is not security theatre: this
database gets copied onto laptops for debugging, and a table of live
credentials would go with it.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

from fpga.errors import Forbidden, ValidationError
from fpga.ids import slug

__all__ = [
    "ROLES",
    "ApiKey",
    "hash_secret",
    "require_role",
]

# Ordered by what they can do, least first.
ROLES: tuple[str, ...] = ("reader", "operator", "admin")


def hash_secret(secret: str) -> str:
    """The digest a key is stored as."""
    if not secret.strip():
        raise ValidationError("a key has a secret", field="secret")
    return hashlib.sha256(secret.strip().encode("utf-8")).hexdigest()


@dataclass(frozen=True, slots=True)
class ApiKey:
    """One credential, without the secret in it."""

    id: str
    label: str
    role: str
    team: str = ""
    active: bool = True

    def __post_init__(self) -> None:
        object.__setattr__(self, "role", slug(self.role))
        if self.role not in ROLES:
            raise ValidationError("no such role", field="role", given=self.role)
        if not self.label.strip():
            raise ValidationError("a key says what it is for", field="label")

    @property
    def rank(self) -> int:
        return ROLES.index(self.role)

    def allows(self, needed: str) -> bool:
        """Whether this key carries at least the role asked for."""
        wanted = slug(needed)
        if wanted not in ROLES:
            raise ValidationError("no such role", field="role", given=needed)
        return self.active and self.rank >= ROLES.index(wanted)

    def describe(self) -> str:
        state = "" if self.active else " (revoked)"
        return f"{self.label} [{self.role}]{state}"


def require_role(key: ApiKey, needed: str) -> ApiKey:
    """Check a key carries a role, and say what it would have needed."""
    if not key.allows(needed):
        raise Forbidden(
            "that key is not allowed to do this",
            needs=slug(needed),
            holds=key.role,
        )
    return key
