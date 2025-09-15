"""The temperature readings themselves, and the questions asked of them.

One reading means very little. The system monitor is noisy at about a degree,
so a single sample crossing a threshold is as likely to be the sensor as the
part. Everything that decides anything reads an average over a window instead,
and the window is short enough that a real excursion is caught inside a few
seconds.

Staleness is the other half and it is the half that gets forgotten. A board
whose last sample is four minutes old is not a cool board, it is a board the
bench has stopped hearing from, and treating missing data as good news is how a
thermal supervisor quietly stops supervising.

The log keeps a bounded number of samples per board. A poll every twenty
seconds for a fortnight is sixty thousand readings, none of which anybody looks
at after the first hour.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from fpga.errors import ValidationError
from fpga.ids import AssetTag, slug
from fpga.temperature import render_temperature

__all__ = [
    "ThermalLog",
    "ThermalSample",
]

_DEFAULT_KEEP = 512


@dataclass(frozen=True, slots=True)
class ThermalSample:
    """One reading off one device."""

    board: str
    at: datetime
    millidegrees: int
    source: str = "sysmon"

    def __post_init__(self) -> None:
        object.__setattr__(self, "board", str(AssetTag.parse(self.board)))
        object.__setattr__(self, "source", slug(self.source))
        if self.at.tzinfo is None:
            raise ValidationError("sample times are in utc", field="at")
        if not -60_000 <= self.millidegrees <= 200_000:
            raise ValidationError(
                "that is not a temperature a device reports",
                field="millidegrees",
                given=self.millidegrees,
            )

    def age(self, now: datetime) -> timedelta:
        return max(now - self.at, timedelta(0))

    def describe(self) -> str:
        return f"{self.board} {render_temperature(self.millidegrees)} at {self.at.isoformat()}"


class ThermalLog:
    """Recent readings, per board."""

    def __init__(self, *, keep: int = _DEFAULT_KEEP) -> None:
        if keep < 2:
            raise ValidationError("keep at least two samples", field="keep", given=keep)
        self.keep = keep
        self._samples: dict[str, list[ThermalSample]] = {}

    def record(self, sample: ThermalSample) -> ThermalSample:
        held = self._samples.setdefault(sample.board, [])
        held.append(sample)
        if len(held) > self.keep:
            del held[: len(held) - self.keep]
        return sample

    def add(
        self,
        board: str,
        at: datetime,
        millidegrees: int,
        *,
        source: str = "sysmon",
    ) -> ThermalSample:
        return self.record(
            ThermalSample(board=board, at=at, millidegrees=millidegrees, source=source)
        )

    def _for(self, board: str) -> list[ThermalSample]:
        return self._samples.get(str(AssetTag.parse(board)), [])

    def boards(self) -> tuple[str, ...]:
        return tuple(sorted(self._samples))

    def latest(self, board: str) -> ThermalSample | None:
        held = self._for(board)
        return held[-1] if held else None

    def since(self, board: str, when: datetime) -> tuple[ThermalSample, ...]:
        return tuple(sample for sample in self._for(board) if sample.at >= when)

    def window(self, board: str, now: datetime, length: timedelta) -> tuple[ThermalSample, ...]:
        if length <= timedelta(0):
            raise ValidationError("a window has some length", field="length")
        return tuple(sample for sample in self._for(board) if now - length <= sample.at <= now)

    def average(self, board: str, now: datetime, length: timedelta) -> int | None:
        """Mean temperature over a window, or nothing if there is no data."""
        samples = self.window(board, now, length)
        if not samples:
            return None
        return round(sum(sample.millidegrees for sample in samples) / len(samples))

    def peak(self, board: str, now: datetime, length: timedelta) -> int | None:
        samples = self.window(board, now, length)
        if not samples:
            return None
        return max(sample.millidegrees for sample in samples)

    def stale(self, board: str, now: datetime, limit: timedelta) -> bool:
        """Whether the bench has stopped hearing from a board.

        A board with no samples at all is stale, not fine. That is the case
        this exists for.
        """
        last = self.latest(board)
        return last is None or last.age(now) > limit

    def rising(self, board: str, now: datetime, length: timedelta) -> bool | None:
        """Whether the second half of the window is warmer than the first.

        Nothing when there is not enough to say, which is fewer than four
        samples, because two noisy readings will happily claim any trend.
        """
        samples = self.window(board, now, length)
        if len(samples) < 4:
            return None
        middle = len(samples) // 2
        first = sum(sample.millidegrees for sample in samples[:middle]) / middle
        second = sum(sample.millidegrees for sample in samples[middle:]) / (len(samples) - middle)
        return second > first

    def prune(self, before: datetime) -> int:
        """Drop everything older than a moment, returning how many went."""
        dropped = 0
        for board, held in list(self._samples.items()):
            keep = [sample for sample in held if sample.at >= before]
            dropped += len(held) - len(keep)
            if keep:
                self._samples[board] = keep
            else:
                del self._samples[board]
        return dropped

    def __len__(self) -> int:
        return sum(len(held) for held in self._samples.values())
