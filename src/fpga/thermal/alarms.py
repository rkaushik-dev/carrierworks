"""Turning readings into a state per board, with the hysteresis applied.

The state machine itself lives with the temperature units, because the
hysteresis rule is a property of thresholds rather than of the bench. What is
here is the bookkeeping around it: which board is in which state, since when,
and what changed at the last update.

Unknown is a state, not a missing value. A board that has stopped reporting is
in a state the bench cannot reason about, and the honest thing is to say so and
let the supervisor decide. Treating it as normal is worse than treating it as
critical, and both are worse than saying it out loud.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from fpga.errors import ValidationError
from fpga.ids import AssetTag
from fpga.temperature import STATES, ThermalLimits, render_temperature
from fpga.thermal.samples import ThermalLog

__all__ = [
    "AlarmBook",
    "ThermalStatus",
]

UNKNOWN = "unknown"


@dataclass(frozen=True, slots=True)
class ThermalStatus:
    """Where one board stands thermally."""

    board: str
    state: str
    since: datetime
    millidegrees: int | None = None
    stale: bool = False

    def __post_init__(self) -> None:
        object.__setattr__(self, "board", str(AssetTag.parse(self.board)))
        if self.state not in (*STATES, UNKNOWN):
            raise ValidationError("no such thermal state", field="state", given=self.state)

    @property
    def healthy(self) -> bool:
        return self.state == "normal"

    @property
    def alarming(self) -> bool:
        """Anything a person should be told about, silence included."""
        return self.state in ("critical", "shutdown", UNKNOWN)

    @property
    def rank(self) -> int:
        """How bad this is, for sorting. Unknown sits with critical."""
        if self.state == UNKNOWN:
            return STATES.index("critical")
        return STATES.index(self.state)

    def held_for(self, now: datetime) -> timedelta:
        return max(now - self.since, timedelta(0))

    def describe(self) -> str:
        if self.state == UNKNOWN:
            return f"{self.board} not reporting"
        reading = (
            "no reading" if self.millidegrees is None else render_temperature(self.millidegrees)
        )
        return f"{self.board} {self.state} at {reading}"


class AlarmBook:
    """The thermal state of every board the bench is watching."""

    def __init__(
        self,
        limits: ThermalLimits,
        *,
        window: timedelta = timedelta(minutes=1),
        stale_after: timedelta = timedelta(minutes=3),
    ) -> None:
        if window <= timedelta(0) or stale_after <= timedelta(0):
            raise ValidationError("both windows have some length", field="alarms")
        self.limits = limits
        self.window = window
        self.stale_after = stale_after
        self._statuses: dict[str, ThermalStatus] = {}

    def status(self, board: str) -> ThermalStatus | None:
        return self._statuses.get(str(AssetTag.parse(board)))

    def update(self, board: str, log: ThermalLog, now: datetime) -> ThermalStatus:
        """Fold the latest readings into this board's state."""
        tag = str(AssetTag.parse(board))
        held = self._statuses.get(tag)
        if log.stale(tag, now, self.stale_after):
            latest = log.latest(tag)
            status = ThermalStatus(
                board=tag,
                state=UNKNOWN,
                since=now if held is None or held.state != UNKNOWN else held.since,
                millidegrees=None if latest is None else latest.millidegrees,
                stale=True,
            )
            self._statuses[tag] = status
            return status
        reading = log.average(tag, now, self.window)
        if reading is None:  # pragma: no cover - a fresh sample means a window
            reading = log.latest(tag).millidegrees  # type: ignore[union-attr]
        previous = held.state if held is not None and held.state != UNKNOWN else "normal"
        state = self.limits.next_state(previous, reading)
        since = now if held is None or held.state != state else held.since
        status = ThermalStatus(board=tag, state=state, since=since, millidegrees=reading)
        self._statuses[tag] = status
        return status

    def update_all(self, log: ThermalLog, now: datetime) -> tuple[ThermalStatus, ...]:
        """Update every board the log knows about, worst first."""
        for board in log.boards():
            self.update(board, log, now)
        return self.worst_first()

    def worst_first(self) -> tuple[ThermalStatus, ...]:
        return tuple(
            sorted(
                self._statuses.values(),
                key=lambda status: (-status.rank, status.board),
            )
        )

    def alarming(self) -> tuple[ThermalStatus, ...]:
        return tuple(status for status in self.worst_first() if status.alarming)

    def in_state(self, state: str) -> tuple[ThermalStatus, ...]:
        return tuple(status for status in self.worst_first() if status.state == state)

    def forget(self, board: str) -> None:
        """Stop watching a board, which is what releasing its lease does."""
        self._statuses.pop(str(AssetTag.parse(board)), None)

    def boards(self) -> tuple[str, ...]:
        return tuple(sorted(self._statuses))

    def __len__(self) -> int:
        return len(self._statuses)
