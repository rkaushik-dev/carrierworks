"""Everything about the bench that a site can set, in one object.

Settings come from the environment because the bench runs as a service and the
service is configured the way every other service in the building is. They are
read once into a frozen object rather than looked up where they are used, so a
change halfway through a run cannot make the first half of a job disagree with
the second.

Every value is checked on the way in and the check has teeth. A lease ceiling
of zero would silently make every lease expire the moment it was taken, and the
place to find that out is at startup rather than at two in the morning.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass, replace
from pathlib import Path

from fpga.errors import ConfigurationError
from fpga.ids import slug

__all__ = [
    "ENV_PREFIX",
    "BenchSettings",
]

ENV_PREFIX = "FPGA_"

_TRUE = frozenset({"1", "true", "yes", "on"})
_FALSE = frozenset({"0", "false", "no", "off"})


def _as_int(values: Mapping[str, str], key: str, default: int) -> int:
    raw = values.get(key)
    if raw is None or raw.strip() == "":
        return default
    try:
        return int(raw.strip())
    except ValueError as exc:
        raise ConfigurationError("setting is not a whole number", setting=key, given=raw) from exc


def _as_bool(values: Mapping[str, str], key: str, default: bool) -> bool:
    raw = values.get(key)
    if raw is None or raw.strip() == "":
        return default
    cleaned = raw.strip().lower()
    if cleaned in _TRUE:
        return True
    if cleaned in _FALSE:
        return False
    raise ConfigurationError("setting is not a yes or a no", setting=key, given=raw)


def _as_text(values: Mapping[str, str], key: str, default: str) -> str:
    raw = values.get(key)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip()


@dataclass(frozen=True, slots=True)
class BenchSettings:
    """One site's settings, already validated."""

    database_url: str = "sqlite+pysqlite:///bench.db"
    artifact_root: Path = Path("var/artifacts")
    journal_root: Path = Path("var/journal")
    site: str = "lab-a"
    default_lease_minutes: int = 60
    max_lease_minutes: int = 480
    lease_grace_seconds: int = 120
    program_attempts: int = 3
    program_backoff_seconds: int = 15
    verify_after_program: bool = True
    thermal_poll_seconds: int = 20
    thermal_stale_seconds: int = 180
    adapter_clock_ceiling_hz: int = 30_000_000
    staffed_hours: str = "08:00-18:00"
    utc_offset_minutes: int = -300
    require_timing_closure: bool = True
    api_key_header: str = "x-bench-key"

    def __post_init__(self) -> None:
        object.__setattr__(self, "site", slug(self.site))
        if self.default_lease_minutes <= 0:
            raise ConfigurationError("a lease lasts some minutes", setting="default_lease_minutes")
        if self.max_lease_minutes < self.default_lease_minutes:
            raise ConfigurationError(
                "the lease ceiling is below the default",
                setting="max_lease_minutes",
                default=self.default_lease_minutes,
                ceiling=self.max_lease_minutes,
            )
        if self.lease_grace_seconds < 0:
            raise ConfigurationError("grace cannot be negative", setting="lease_grace_seconds")
        if not 1 <= self.program_attempts <= 10:
            raise ConfigurationError(
                "program attempts are out of range", setting="program_attempts"
            )
        if self.program_backoff_seconds < 0:
            raise ConfigurationError("backoff cannot be negative", setting="program_backoff")
        if self.thermal_poll_seconds <= 0:
            raise ConfigurationError(
                "the thermal poll has a period", setting="thermal_poll_seconds"
            )
        if self.thermal_stale_seconds <= self.thermal_poll_seconds:
            raise ConfigurationError(
                "a sample would be stale before the next poll",
                setting="thermal_stale_seconds",
                poll=self.thermal_poll_seconds,
                stale=self.thermal_stale_seconds,
            )
        if not 100_000 <= self.adapter_clock_ceiling_hz <= 60_000_000:
            raise ConfigurationError("adapter ceiling is implausible", setting="adapter_clock")
        if not -720 <= self.utc_offset_minutes <= 840:
            raise ConfigurationError("that offset is not on the planet", setting="utc_offset")
        if not self.api_key_header.strip():
            raise ConfigurationError("the key header needs a name", setting="api_key_header")

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> BenchSettings:
        """Read the settings out of the environment, prefix and all."""
        source = os.environ if environ is None else environ
        values = {
            key[len(ENV_PREFIX) :].lower(): value
            for key, value in source.items()
            if key.startswith(ENV_PREFIX)
        }
        defaults = cls()
        return cls(
            database_url=_as_text(values, "database_url", defaults.database_url),
            artifact_root=Path(_as_text(values, "artifact_root", str(defaults.artifact_root))),
            journal_root=Path(_as_text(values, "journal_root", str(defaults.journal_root))),
            site=_as_text(values, "site", defaults.site),
            default_lease_minutes=_as_int(
                values, "default_lease_minutes", defaults.default_lease_minutes
            ),
            max_lease_minutes=_as_int(values, "max_lease_minutes", defaults.max_lease_minutes),
            lease_grace_seconds=_as_int(
                values, "lease_grace_seconds", defaults.lease_grace_seconds
            ),
            program_attempts=_as_int(values, "program_attempts", defaults.program_attempts),
            program_backoff_seconds=_as_int(
                values, "program_backoff_seconds", defaults.program_backoff_seconds
            ),
            verify_after_program=_as_bool(
                values, "verify_after_program", defaults.verify_after_program
            ),
            thermal_poll_seconds=_as_int(
                values, "thermal_poll_seconds", defaults.thermal_poll_seconds
            ),
            thermal_stale_seconds=_as_int(
                values, "thermal_stale_seconds", defaults.thermal_stale_seconds
            ),
            adapter_clock_ceiling_hz=_as_int(
                values, "adapter_clock_ceiling_hz", defaults.adapter_clock_ceiling_hz
            ),
            staffed_hours=_as_text(values, "staffed_hours", defaults.staffed_hours),
            utc_offset_minutes=_as_int(values, "utc_offset_minutes", defaults.utc_offset_minutes),
            require_timing_closure=_as_bool(
                values, "require_timing_closure", defaults.require_timing_closure
            ),
            api_key_header=_as_text(values, "api_key_header", defaults.api_key_header),
        )

    def override(self, **changes: object) -> BenchSettings:
        """A copy with a few settings changed, checked the same way."""
        unknown = set(changes) - {field.name for field in self.__dataclass_fields__.values()}
        if unknown:
            raise ConfigurationError("no such setting", setting=", ".join(sorted(unknown)))
        return replace(self, **changes)  # type: ignore[arg-type]

    @property
    def lease_ceiling_seconds(self) -> int:
        return self.max_lease_minutes * 60 + self.lease_grace_seconds
