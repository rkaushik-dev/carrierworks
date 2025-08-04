"""The error hierarchy every layer of the bench raises.

Two things are load bearing here and both exist because of how the bench gets
used. First, every error carries a stable ``code``. The web layer puts it in a
JSON envelope and the operators grep the job logs for it, so renaming one is a
breaking change even though nothing in Python cares. Second, an error says
whether the operation that raised it is worth trying again. A programming job
that fails because the cable fell out should come back around; one that fails
because the bitstream targets the wrong device never will, and retrying it just
burns a slot on the queue.
"""

from __future__ import annotations

from typing import Any

__all__ = [
    "BenchError",
    "CatalogError",
    "ChainError",
    "ConfigurationError",
    "Conflict",
    "DeviceMismatch",
    "NotFound",
    "TransientBenchError",
    "ValidationError",
]


class BenchError(Exception):
    """Base for everything the bench raises on purpose.

    Anything that escapes without being one of these is a defect, and the job
    runner treats it that way: an unknown exception fails the job and does not
    retry it.
    """

    code = "bench_error"
    retryable = False

    def __init__(self, message: str, **details: Any) -> None:
        super().__init__(message)
        self.message = message
        self.details: dict[str, Any] = dict(details)

    def __str__(self) -> str:
        if not self.details:
            return self.message
        rendered = ", ".join(f"{key}={value!r}" for key, value in sorted(self.details.items()))
        return f"{self.message} ({rendered})"

    def as_dict(self) -> dict[str, Any]:
        """The shape the http layer serialises and the job log records."""
        return {
            "code": self.code,
            "message": self.message,
            "retryable": self.retryable,
            "details": dict(self.details),
        }


class TransientBenchError(BenchError):
    """Something out in the lab was unhappy and may not be next time.

    Cables, adapters, power distribution units and the flash chips themselves
    all fail intermittently. Subclasses of this get another go.
    """

    code = "transient"
    retryable = True


class ChainError(TransientBenchError):
    """The scan chain did not read back as a chain.

    Retryable, and the reason is the ribbon cable. A chain that reads as all
    ones once and correctly the second time is the normal way a loose header
    presents, so the job runner gets to try again before anyone is called.
    """

    code = "chain"


class ConfigurationError(BenchError):
    """The bench was described wrong, in a file or a database row."""

    code = "configuration"


class ValidationError(BenchError):
    """A value handed in from outside does not hold up."""

    code = "validation"

    def __init__(self, message: str, *, field: str | None = None, **details: Any) -> None:
        if field is not None:
            details["field"] = field
        super().__init__(message, **details)


class Conflict(BenchError):
    """Two things wanted the same board, lease or number at the same time.

    Not a validation failure: what was asked for was perfectly sensible and
    somebody else got there first. The http layer turns this into a 409 and
    the scheduler treats it as a reason to look at the next candidate rather
    than as a reason to stop.
    """

    code = "conflict"


class NotFound(BenchError):
    """Asked for a thing the bench has never heard of."""

    code = "not_found"

    def __init__(self, kind: str, key: str) -> None:
        super().__init__(f"no {kind} known as {key}", kind=kind, key=key)
        self.kind = kind
        self.key = key


class CatalogError(ConfigurationError):
    """The device, flash or card catalog disagrees with itself."""

    code = "catalog"


class DeviceMismatch(BenchError):
    """An image and a device were paired that do not belong together.

    Kept separate from :class:`ValidationError` because this one is the single
    most expensive mistake the bench can make. Loading a Kintex image into an
    Artix will not brick the part, but it will burn an hour of somebody's
    afternoon before anyone thinks to check the idcode.
    """

    code = "device_mismatch"

    def __init__(self, message: str, *, expected: str, found: str, **details: Any) -> None:
        super().__init__(message, expected=expected, found=found, **details)
        self.expected = expected
        self.found = found
