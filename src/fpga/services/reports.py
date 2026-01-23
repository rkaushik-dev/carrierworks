"""Reading the build's own reports in, and answering what they mean.

The bench never runs synthesis, so everything it knows about a design's timing
arrives as two text files somebody uploads beside the image. This service is
where they stop being text: parsed once, stored as numbers, and after that read
by the promotion gate and the regression report without either of them knowing
what a Vivado report looks like.

Regressions are computed against the design's baseline rather than against the
previous build. The baseline is the build somebody was happy with, which is a
different thing from the last one, and comparing against the last one hides a
slow slide where every build is slightly worse than the one before it.
"""

from __future__ import annotations

from datetime import datetime

from fpga.errors import NotFound, ValidationError
from fpga.persistence.unit import UnitOfWork
from fpga.reports.baselines import BuildReport, compare
from fpga.reports.timing import parse_timing_report
from fpga.reports.utilization import parse_utilization_report

__all__ = [
    "baseline",
    "ingest",
    "regressions",
    "set_baseline",
    "timing_for",
]


def ingest(
    unit: UnitOfWork,
    digest: str,
    now: datetime,
    *,
    timing_text: str,
    utilization_text: str,
) -> BuildReport:
    """Parse a build's two reports and store the numbers against its image."""
    record = unit.artifacts.get(digest)
    if unit.reports.for_digest(digest) is not None:
        raise ValidationError(
            "that image already has its reports read",
            field="digest",
            digest=record.short_digest,
        )
    report = BuildReport(
        digest=digest,
        design=record.design,
        at=now,
        timing=parse_timing_report(timing_text),
        utilization=parse_utilization_report(utilization_text),
    )
    unit.reports.add(report, read_at=now)
    if unit.reports.baseline(record.design) is None and report.closed:
        unit.session.flush()
        unit.reports.set_baseline(digest)
    return report


def timing_for(unit: UnitOfWork, digest: str) -> tuple[bool | None, int | None]:
    """Whether an image closed timing and by how much, or nothing if unread."""
    report = unit.reports.for_digest(digest)
    if report is None:
        return (None, None)
    return (report.timing.met, report.timing.worst_slack_ps)


def baseline(unit: UnitOfWork, design: str) -> BuildReport | None:
    return unit.reports.baseline(design)


def set_baseline(unit: UnitOfWork, digest: str) -> BuildReport:
    """Pin a design's baseline to a build somebody was happy with."""
    if unit.reports.for_digest(digest) is None:
        raise NotFound("build report", digest)
    return unit.reports.set_baseline(digest)


def regressions(unit: UnitOfWork, digest: str) -> tuple[str, ...]:
    """How a build compares with its design's baseline, in words."""
    report = unit.reports.for_digest(digest)
    if report is None:
        raise NotFound("build report", digest)
    against = unit.reports.baseline(report.design)
    if against is None or against.digest == digest:
        return ()
    return compare(against, report)
