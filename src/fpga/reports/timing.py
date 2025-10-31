"""Reading the timing summary the place and route tool writes.

The report is a text file with a table in it, and the table has moved twice in
the versions this lab has used, so the parser hunts for the header row rather
than counting lines. What it wants is the eight numbers on the summary row:
worst and total slack for setup and for hold, and the failing and total
endpoint counts for each.

Hold is the number people forget. A design with negative setup slack runs at a
lower clock and is annoying. A design with negative hold slack does not work at
any clock, and it is the one that gets shipped because everybody looked at the
setup column.

Slack is kept in picoseconds because the reports print three decimals of a
nanosecond and adding a few thousand of those as floats is how a total ends up
disagreeing with the tool by a picosecond, which then looks like a regression.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from fpga.errors import ValidationError
from fpga.ids import design_name
from fpga.measure import picoseconds_from_ns, render_ns

__all__ = [
    "ClockConstraint",
    "TimingSummary",
    "parse_timing_report",
]

_NUMBER = r"-?\d+(?:\.\d+)?"
_SUMMARY_ROW = re.compile(
    r"^\|?\s*(?P<wns>" + _NUMBER + r")\s*\|"
    r"\s*(?P<tns>" + _NUMBER + r")\s*\|"
    r"\s*(?P<tns_failing>\d+)\s*\|"
    r"\s*(?P<tns_total>\d+)\s*\|"
    r"\s*(?P<whs>" + _NUMBER + r")\s*\|"
    r"\s*(?P<ths>" + _NUMBER + r")\s*\|"
    r"\s*(?P<ths_failing>\d+)\s*\|"
    r"\s*(?P<ths_total>\d+)\s*\|?\s*$"
)
_CLOCK_ROW = re.compile(
    r"^\s*(?P<name>[a-zA-Z][\w/\[\]\.]*)\s+"
    r"\{(?P<waveform>[^}]*)\}\s+"
    r"(?P<period>" + _NUMBER + r")\s+"
    r"(?P<frequency>" + _NUMBER + r")\s*$"
)
# The colon is required: the report's own heading is "Design Timing Summary"
# and matching that gives every design the name timing.
_DESIGN = re.compile(r"^\s*\|?\s*Design\s*:\s*(?P<design>[\w\-/\.]+)", re.IGNORECASE)


@dataclass(frozen=True, slots=True)
class ClockConstraint:
    """One clock the design was constrained with."""

    name: str
    period_ps: int
    frequency_mhz: float

    def describe(self) -> str:
        return f"{self.name} {self.frequency_mhz:.3f} MHz"


@dataclass(frozen=True, slots=True)
class TimingSummary:
    """The eight numbers that say whether a design closed."""

    wns_ps: int
    tns_ps: int
    setup_failing: int
    setup_total: int
    whs_ps: int
    ths_ps: int
    hold_failing: int
    hold_total: int
    design: str = ""
    clocks: tuple[ClockConstraint, ...] = ()

    def __post_init__(self) -> None:
        if self.design:
            object.__setattr__(self, "design", design_name(self.design))
        for count in (self.setup_failing, self.setup_total, self.hold_failing, self.hold_total):
            if count < 0:
                raise ValidationError("endpoint counts are not negative", field="endpoints")
        if self.setup_failing > self.setup_total or self.hold_failing > self.hold_total:
            raise ValidationError(
                "more endpoints failed than exist",
                field="endpoints",
                setup=f"{self.setup_failing}/{self.setup_total}",
                hold=f"{self.hold_failing}/{self.hold_total}",
            )

    @property
    def setup_met(self) -> bool:
        return self.wns_ps >= 0 and self.setup_failing == 0

    @property
    def hold_met(self) -> bool:
        return self.whs_ps >= 0 and self.hold_failing == 0

    @property
    def met(self) -> bool:
        """Whether the design closed, both ways."""
        return self.setup_met and self.hold_met

    @property
    def worst_slack_ps(self) -> int:
        """The worse of the two worst slacks, which is what a gate reads."""
        return min(self.wns_ps, self.whs_ps)

    @property
    def failing_endpoints(self) -> int:
        return self.setup_failing + self.hold_failing

    def fastest_clock(self) -> ClockConstraint | None:
        if not self.clocks:
            return None
        return max(self.clocks, key=lambda clock: clock.frequency_mhz)

    def describe(self) -> str:
        verdict = "closed" if self.met else "did not close"
        name = f"{self.design} " if self.design else ""
        return (
            f"{name}{verdict}: wns {render_ns(self.wns_ps)} ns, "
            f"whs {render_ns(self.whs_ps)} ns, {self.failing_endpoints} failing"
        )


def parse_timing_report(text: str) -> TimingSummary:
    """Pull the summary out of a report, wherever the tool put it."""
    design = ""
    clocks: list[ClockConstraint] = []
    summary: TimingSummary | None = None
    for line in text.splitlines():
        if not design:
            found = _DESIGN.match(line)
            if found:
                design = found.group("design")
        clock = _CLOCK_ROW.match(line)
        if clock:
            clocks.append(
                ClockConstraint(
                    name=clock.group("name"),
                    period_ps=picoseconds_from_ns(clock.group("period")),
                    frequency_mhz=float(clock.group("frequency")),
                )
            )
            continue
        row = _SUMMARY_ROW.match(line.strip())
        if row and summary is None:
            summary = TimingSummary(
                wns_ps=picoseconds_from_ns(row.group("wns")),
                tns_ps=picoseconds_from_ns(row.group("tns")),
                setup_failing=int(row.group("tns_failing")),
                setup_total=int(row.group("tns_total")),
                whs_ps=picoseconds_from_ns(row.group("whs")),
                ths_ps=picoseconds_from_ns(row.group("ths")),
                hold_failing=int(row.group("ths_failing")),
                hold_total=int(row.group("ths_total")),
            )
    if summary is None:
        raise ValidationError("no timing summary in that report", field="report")
    return TimingSummary(
        wns_ps=summary.wns_ps,
        tns_ps=summary.tns_ps,
        setup_failing=summary.setup_failing,
        setup_total=summary.setup_total,
        whs_ps=summary.whs_ps,
        ths_ps=summary.ths_ps,
        hold_failing=summary.hold_failing,
        hold_total=summary.hold_total,
        design=design,
        clocks=tuple(clocks),
    )
