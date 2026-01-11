"""Taking an image in, and moving it between channels.

Ingest writes twice on purpose: the bytes go into the content addressed store
on disk and a row goes into the database. The store is the truth about the
image and the row is the truth about what the bench thinks of it, and keeping
them apart means a channel change never rewrites a file.

An image that is already stored is not an error. The nightly build produces the
same bytes for an unchanged design every night, and the second ingest hands
back the record that is already there.

Promotion asks the rules rather than deciding anything, and it writes only when
the rules say yes. The evidence comes from the caller because it is gathered
from four places and this service should not know about any of them.
"""

from __future__ import annotations

from datetime import datetime

from fpga.artifacts.channels import PromotionDecision, PromotionEvidence, evaluate_promotion
from fpga.artifacts.provenance import Provenance
from fpga.artifacts.registry import ArtifactRecord, ArtifactStore
from fpga.bitstream.image import Bitstream
from fpga.errors import Conflict, NotFound
from fpga.jobs.store import JobStore
from fpga.persistence.unit import UnitOfWork

__all__ = [
    "counted_loads",
    "evidence_from",
    "ingest",
    "promote",
    "record_build",
]


def ingest(
    unit: UnitOfWork,
    store: ArtifactStore,
    image: Bitstream,
    now: datetime,
    *,
    channel: str = "dev",
) -> ArtifactRecord:
    """Store an image and record it, or hand back the one already there."""
    stored = store.put(image, channel=channel)
    try:
        return unit.artifacts.get(stored.digest)
    except NotFound:
        return unit.artifacts.add(stored, stored_at=now)


def promote(
    unit: UnitOfWork,
    digest: str,
    channel: str,
    evidence: PromotionEvidence | None = None,
    *,
    soak_loads: int = 3,
) -> PromotionDecision:
    """Move an image to another channel if the rules allow it.

    The decision comes back whether or not it was allowed, so a caller can
    show the blockers. A refusal is a conflict rather than a validation
    failure: what was asked for is perfectly sensible and the image is simply
    not ready.
    """
    record = unit.artifacts.get(digest)
    decision = evaluate_promotion(record, channel, evidence, soak_loads=soak_loads)
    if not decision.allowed:
        raise Conflict(
            "that image cannot move channel yet",
            digest=record.short_digest,
            to=decision.to_channel,
            blockers="; ".join(decision.blockers),
        )
    unit.artifacts.save(record.with_channel(decision.to_channel))
    return decision


def counted_loads(unit: UnitOfWork, digest: str) -> tuple[int, int]:
    """How many loads of an image passed and how many failed on a board.

    Reads the job rows rather than an in memory store, because the evidence
    for a promotion has to survive a restart of whatever asked for it.
    """
    passed = 0
    failed = 0
    for job in unit.jobs.for_digest(digest):
        if job.succeeded:
            passed += 1
        elif job.state == "failed" and job.error_code not in ("gate", "abandoned"):
            failed += 1
    return (passed, failed)


def record_build(unit: UnitOfWork, build: Provenance) -> Provenance:
    """Write down the build that produced an image."""
    unit.artifacts.get(build.digest)
    return unit.provenance.add(build)


def evidence_from(
    unit: UnitOfWork,
    digest: str,
    *,
    timing_closed: bool | None = None,
    approvals: tuple[str, ...] = (),
) -> PromotionEvidence:
    """The evidence a promotion reads, out of the database and the caller."""
    passed, failed = counted_loads(unit, digest)
    return PromotionEvidence(
        timing_closed=timing_closed,
        successful_loads=passed,
        failed_loads=failed,
        approvals=tuple(sorted(set(approvals))),
        provenance=unit.provenance.for_digest(digest),
        disagreements=unit.provenance.disagreements(digest),
    )


def load_counts_from_store(jobs: JobStore, digest: str) -> tuple[int, int]:
    """The same count against an in memory job store, for the dry run mode."""
    passed = sum(1 for job in jobs.all() if job.digest == digest and job.succeeded)
    failed = sum(
        1
        for job in jobs.all()
        if job.digest == digest
        and job.state == "failed"
        and job.error_code not in ("gate", "abandoned")
    )
    return (passed, failed)
