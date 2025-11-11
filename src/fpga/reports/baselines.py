"""Comparing a build against the last one anybody was happy with.

A design closing timing is not the interesting question after the first week.
The interesting question is whether it closed by less than it used to, because
a design whose worst slack has fallen from four hundred picoseconds to twenty
still passes and is one commit away from not passing.

So a baseline is kept per design, and it is set deliberately rather than
being whatever ran last. The baseline is the build somebody was happy with, and
a build that regresses against it is reported even when it passes on its own
terms.

Thresholds are in the comparison rather than in the caller. Slack falling by
under fifty picoseconds is noise from a different placement seed; logic growing
by under two percent is somebody adding a debug register. Both are things this
lab decided after the first month of a report that cried wolf every night.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from fpga.errors import NotFound, ValidationError
from fpga.ids import design_name
from fpga.measure import render_ns
from fpga.reports.timing import TimingSummary
from fpga.reports.utilization import Utilization

__all__ = [
    "BaselineBook",
    "BuildReport",
    "compare",
]

# Below these, a difference is placement noise rather than a regression.
_SLACK_NOISE_PS = 50
_UTILISATION_NOISE = 2.0


@dataclass(frozen=True, slots=True)
class BuildReport:
    """One build's reports, tied to the image they describe."""

    digest: str
    design: str
    at: datetime
    timing: TimingSummary
    utilization: Utilization
    revision: str = ""

    def __post_init__(self) -> None:
        object.__setattr__(self, "design", design_name(self.design))
        if len(self.digest) != 64:
            raise ValidationError("a report carries a full digest", field="digest")
        if self.at.tzinfo is None:
            raise ValidationError("report times are in utc", field="at")

    @property
    def short_digest(self) -> str:
        return self.digest[:12]

    @property
    def closed(self) -> bool:
        return self.timing.met

    def describe(self) -> str:
        return f"{self.design} {self.short_digest}: {self.timing.describe()}"


def compare(baseline: BuildReport, candidate: BuildReport) -> tuple[str, ...]:
    """Every way the candidate is worse than the baseline, in words."""
    if baseline.design != candidate.design:
        raise ValidationError(
            "those two reports are for different designs",
            field="design",
            baseline=baseline.design,
            candidate=candidate.design,
        )
    found: list[str] = []
    slack_drop = baseline.timing.worst_slack_ps - candidate.timing.worst_slack_ps
    if slack_drop > _SLACK_NOISE_PS:
        found.append(
            f"worst slack fell from {render_ns(baseline.timing.worst_slack_ps)} to "
            f"{render_ns(candidate.timing.worst_slack_ps)} ns"
        )
    if candidate.timing.failing_endpoints > baseline.timing.failing_endpoints:
        found.append(
            f"failing endpoints went from {baseline.timing.failing_endpoints} to "
            f"{candidate.timing.failing_endpoints}"
        )
    if baseline.closed and not candidate.closed:
        found.append("this build does not close timing and the baseline did")
    for kind in ("luts", "registers", "block_ram", "dsp"):
        before = baseline.utilization.row(kind)
        after = candidate.utilization.row(kind)
        if before is None or after is None:
            continue
        growth = after.percent - before.percent
        if growth > _UTILISATION_NOISE:
            found.append(f"{after.name} went from {before.percent:.1f}% to {after.percent:.1f}%")
    return tuple(found)


class BaselineBook:
    """Every build report, and which one is the baseline for each design."""

    def __init__(self) -> None:
        self._reports: dict[str, list[BuildReport]] = {}
        self._baselines: dict[str, str] = {}

    def record(self, report: BuildReport) -> BuildReport:
        """Keep a report, and make it the baseline if the design has none."""
        held = self._reports.setdefault(report.design, [])
        held.append(report)
        if report.design not in self._baselines and report.closed:
            self._baselines[report.design] = report.digest
        return report

    def reports(self, design: str) -> tuple[BuildReport, ...]:
        return tuple(self._reports.get(design_name(design), ()))

    def latest(self, design: str) -> BuildReport | None:
        held = self.reports(design)
        return max(held, key=lambda report: report.at) if held else None

    def report_for(self, digest: str) -> BuildReport:
        for held in self._reports.values():
            for report in held:
                if report.digest == digest:
                    return report
        raise NotFound("build report", digest)

    def baseline(self, design: str) -> BuildReport | None:
        digest = self._baselines.get(design_name(design))
        return None if digest is None else self.report_for(digest)

    def set_baseline(self, design: str, digest: str) -> BuildReport:
        """Pin a design's baseline to a build somebody was happy with."""
        report = self.report_for(digest)
        if report.design != design_name(design):
            raise ValidationError(
                "that build is for another design",
                field="digest",
                design=design_name(design),
                found=report.design,
            )
        self._baselines[report.design] = digest
        return report

    def regressions(self, report: BuildReport) -> tuple[str, ...]:
        """How this build compares with its design's baseline."""
        baseline = self.baseline(report.design)
        if baseline is None or baseline.digest == report.digest:
            return ()
        return compare(baseline, report)

    def designs(self) -> tuple[str, ...]:
        return tuple(sorted(self._reports))

    def __len__(self) -> int:
        return sum(len(held) for held in self._reports.values())
