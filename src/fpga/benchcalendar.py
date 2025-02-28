"""When the bench is staffed, and when it is nobody's to touch.

Two different questions live here and they are not the same one. Staffed hours
say whether a person is in the lab to reseat a cable, which decides whether a
job that needs hands may be queued at all. Maintenance windows say the bench or
one rack is out of use, which stops even the jobs that need nothing from anyone.

Times are UTC everywhere, and the calendar carries a fixed offset for the wall
clock the lab keeps. The offset does not follow daylight saving on purpose. The
overnight regression slot is defined against the building's air handling
schedule, which is set once a year and does not shift in March, and having the
bench move an hour away from the cooling was worth avoiding.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone

from fpga.errors import ValidationError
from fpga.ids import slug

__all__ = [
    "BenchCalendar",
    "MaintenanceWindow",
    "ServiceHours",
]

_MINUTES_IN_DAY = 24 * 60


@dataclass(frozen=True, slots=True)
class ServiceHours:
    """The staffed window on one weekday, in minutes past local midnight."""

    opens: int
    closes: int

    def __post_init__(self) -> None:
        if not 0 <= self.opens < self.closes <= _MINUTES_IN_DAY:
            raise ValidationError(
                "service hours have to open before they close",
                field="hours",
                opens=self.opens,
                closes=self.closes,
            )

    @classmethod
    def parse(cls, text: str) -> ServiceHours:
        """From the form the rota is written in, such as 08:00-18:30."""
        cleaned = text.strip().replace(" ", "")
        start, sep, end = cleaned.partition("-")
        if not sep:
            raise ValidationError("service hours look like 08:00-18:00", field="hours")
        return cls(opens=_minutes(start), closes=_minutes(end))

    def covers(self, minute_of_day: int) -> bool:
        return self.opens <= minute_of_day < self.closes


def _minutes(text: str) -> int:
    hours, sep, minutes = text.partition(":")
    if not sep or not hours.isdigit() or not minutes.isdigit():
        raise ValidationError("a time looks like 08:00", field="time", given=text)
    total = int(hours) * 60 + int(minutes)
    if not 0 <= total <= _MINUTES_IN_DAY or int(minutes) >= 60:
        raise ValidationError("a time is inside one day", field="time", given=text)
    return total


@dataclass(frozen=True, slots=True)
class MaintenanceWindow:
    """A stretch where some or all of the bench is out of use."""

    start: datetime
    end: datetime
    reason: str
    scope: str = "bench"

    def __post_init__(self) -> None:
        for moment in (self.start, self.end):
            if moment.tzinfo is None:
                raise ValidationError("maintenance windows are in utc", field="window")
        if self.end <= self.start:
            raise ValidationError("a window ends after it starts", field="window")
        object.__setattr__(self, "scope", slug(self.scope))

    @property
    def minutes(self) -> int:
        return int((self.end - self.start).total_seconds() // 60)

    def covers(self, when: datetime) -> bool:
        return self.start <= when < self.end

    def applies_to(self, rack: str | None) -> bool:
        """A bench wide window covers every rack; a rack window covers one."""
        if self.scope == "bench":
            return True
        return rack is not None and slug(rack) == self.scope

    def overlaps(self, other: MaintenanceWindow) -> bool:
        return self.start < other.end and other.start < self.end


@dataclass(frozen=True, slots=True)
class BenchCalendar:
    """Staffed hours, holidays and maintenance, for one bench."""

    hours: dict[int, ServiceHours] = field(default_factory=dict)
    holidays: frozenset[date] = field(default_factory=frozenset)
    windows: tuple[MaintenanceWindow, ...] = ()
    utc_offset_minutes: int = -300

    @classmethod
    def weekdays(
        cls,
        text: str = "08:00-18:00",
        *,
        holidays: frozenset[date] | None = None,
        utc_offset_minutes: int = -300,
    ) -> BenchCalendar:
        """The usual shape: monday to friday, one window a day."""
        hours = dict.fromkeys(range(5), ServiceHours.parse(text))
        return cls(
            hours=hours,
            holidays=holidays or frozenset(),
            utc_offset_minutes=utc_offset_minutes,
        )

    def local(self, when: datetime) -> datetime:
        if when.tzinfo is None:
            raise ValidationError("the calendar works in utc", field="when")
        return when.astimezone(timezone(timedelta(minutes=self.utc_offset_minutes)))

    def is_staffed(self, when: datetime) -> bool:
        local = self.local(when)
        if local.date() in self.holidays:
            return False
        hours = self.hours.get(local.weekday())
        if hours is None:
            return False
        return hours.covers(local.hour * 60 + local.minute)

    def maintenance_at(self, when: datetime, rack: str | None = None) -> MaintenanceWindow | None:
        for window in self.windows:
            if window.covers(when) and window.applies_to(rack):
                return window
        return None

    def blocked(self, when: datetime, rack: str | None = None) -> str | None:
        """Why work cannot happen now, or nothing when it can."""
        window = self.maintenance_at(when, rack)
        if window is not None:
            return f"maintenance: {window.reason}"
        if not self.is_staffed(when):
            return "outside staffed hours"
        return None

    def next_staffed(self, when: datetime, *, horizon_days: int = 14) -> datetime | None:
        """The next minute somebody is in the lab, searched a minute at a time.

        A minute is coarse enough to be cheap and fine enough that nobody
        notices, and the horizon stops a bench with no hours at all from
        looping forever.
        """
        candidate = when.replace(second=0, microsecond=0)
        limit = when + timedelta(days=horizon_days)
        while candidate <= limit:
            if self.is_staffed(candidate) and self.maintenance_at(candidate) is None:
                return candidate
            candidate += timedelta(minutes=1)
        return None

    def with_window(self, window: MaintenanceWindow) -> BenchCalendar:
        for existing in self.windows:
            if existing.scope == window.scope and existing.overlaps(window):
                raise ValidationError(
                    "that window overlaps one already booked",
                    field="window",
                    scope=window.scope,
                )
        return BenchCalendar(
            hours=dict(self.hours),
            holidays=self.holidays,
            windows=(*self.windows, window),
            utc_offset_minutes=self.utc_offset_minutes,
        )
