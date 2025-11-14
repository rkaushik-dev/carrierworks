"""Gathering everything known about an image into the shape promotion reads.

The promotion rules are written against a single evidence object on purpose:
they are the part people argue about, and an argument is much shorter when the
inputs are one thing anyone can print. This module is what fills that object
in, and it is the only place that knows where each fact lives.

Timing comes from the build reports, load history comes from the job store,
provenance comes from its own log, and approvals come from whoever typed them.
Four sources, one shape, and no rule anywhere else has to know that a load
count means counting passed jobs whose digest matches.

Counting loads is worth one note. Only jobs that actually reached the board
count, which means gate refusals are not failures of the image. A job refused
because somebody else held the board says nothing about whether the image is
good, and letting it block a promotion would make the queue a lottery.
"""

from __future__ import annotations

from fpga.artifacts.channels import PromotionEvidence
from fpga.artifacts.provenance import ProvenanceLog
from fpga.jobs.models import ProgramJob
from fpga.jobs.store import JobStore
from fpga.reports.baselines import BaselineBook

__all__ = [
    "evidence_for",
    "load_counts",
    "timing_for",
]

_GATE_CODES = frozenset({"gate", "abandoned"})


def timing_for(baselines: BaselineBook, digest: str) -> tuple[bool | None, int | None]:
    """Whether this image closed timing, and by how much.

    Nothing when no report has been read for it, which is a different answer
    from a report saying it failed and the gates treat it that way.
    """
    try:
        report = baselines.report_for(digest)
    except Exception:  # noqa: BLE001 - an image with no report is the normal case
        return (None, None)
    return (report.timing.met, report.timing.worst_slack_ps)


def _reached_the_board(job: ProgramJob) -> bool:
    return job.error_code not in _GATE_CODES


def load_counts(jobs: JobStore, digest: str) -> tuple[int, int]:
    """How many loads of this image passed, and how many failed on a board."""
    passed = 0
    failed = 0
    for job in jobs.all():
        if job.digest != digest:
            continue
        if job.succeeded:
            passed += 1
        elif job.state == "failed" and _reached_the_board(job):
            failed += 1
    return (passed, failed)


def evidence_for(
    digest: str,
    *,
    baselines: BaselineBook,
    jobs: JobStore,
    provenance: ProvenanceLog | None = None,
    approvals: tuple[str, ...] = (),
) -> PromotionEvidence:
    """Everything the bench knows about one image, in one object."""
    closed, slack = timing_for(baselines, digest)
    passed, failed = load_counts(jobs, digest)
    builds = () if provenance is None else provenance.for_digest(digest)
    disagreements = () if provenance is None else provenance.disagreements(digest)
    return PromotionEvidence(
        timing_closed=closed,
        worst_slack_ps=slack,
        successful_loads=passed,
        failed_loads=failed,
        approvals=tuple(sorted(set(approvals))),
        provenance=builds,
        disagreements=disagreements,
    )
