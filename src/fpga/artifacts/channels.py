"""Which images are allowed where, and what has to be true first.

Three channels, and the difference between them is trust rather than content.
Anything a build produces lands in dev. An image that has closed timing and can
be rebuilt from a clean tree may be promoted to qual, which is what the nightly
regression runs from. Golden is the image that goes into the flash on a board
that ships to a customer demo, and getting there needs a person to say so.

The rules are written as data because they get argued about. Twice now somebody
has wanted a partial image in qual for a specific regression, and both times the
answer was no, and having the reason as a line rather than as a condition
buried in a function is what made that a two minute conversation.

Nothing here promotes anything. It decides, and it says why, and the caller
does the writing.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from fpga.artifacts.provenance import Provenance
from fpga.artifacts.registry import ArtifactRecord
from fpga.errors import ValidationError
from fpga.ids import slug

__all__ = [
    "CHANNELS",
    "PromotionDecision",
    "PromotionEvidence",
    "channel_rank",
    "evaluate_promotion",
]

# Ordered by trust, least first.
CHANNELS: tuple[str, ...] = ("dev", "qual", "golden")


def channel_rank(channel: str) -> int:
    name = slug(channel)
    if name not in CHANNELS:
        raise ValidationError("no such channel", field="channel", given=channel)
    return CHANNELS.index(name)


@dataclass(frozen=True, slots=True)
class PromotionEvidence:
    """Everything the bench has learned about an image since it arrived."""

    timing_closed: bool | None = None
    worst_slack_ps: int | None = None
    successful_loads: int = 0
    failed_loads: int = 0
    approvals: tuple[str, ...] = ()
    provenance: tuple[Provenance, ...] = field(default_factory=tuple)
    disagreements: tuple[str, ...] = ()

    @property
    def load_attempts(self) -> int:
        return self.successful_loads + self.failed_loads

    @property
    def reproducible(self) -> bool:
        """Every recorded build has to be reproducible, not just one of them."""
        return bool(self.provenance) and all(build.reproducible for build in self.provenance)


@dataclass(frozen=True, slots=True)
class PromotionDecision:
    """Whether an image may move, and everything standing in the way."""

    digest: str
    from_channel: str
    to_channel: str
    blockers: tuple[str, ...] = ()

    @property
    def allowed(self) -> bool:
        return not self.blockers

    def describe(self) -> str:
        move = f"{self.from_channel} to {self.to_channel}"
        if self.allowed:
            return f"{self.digest[:12]} may go from {move}"
        return f"{self.digest[:12]} cannot go from {move}: " + "; ".join(self.blockers)


def _qual_blockers(record: ArtifactRecord, evidence: PromotionEvidence) -> list[str]:
    blockers: list[str] = []
    if record.is_partial:
        blockers.append("a partial image cannot be qualified on its own")
    if evidence.timing_closed is None:
        blockers.append("no timing report has been read for this image")
    elif not evidence.timing_closed:
        blockers.append("the design did not close timing")
    if not evidence.provenance:
        blockers.append("nothing recorded where this image came from")
    elif not evidence.reproducible:
        blockers.append("the build cannot be reproduced from a clean tree")
    return blockers


def _golden_blockers(evidence: PromotionEvidence, *, soak: int) -> list[str]:
    blockers: list[str] = []
    if not evidence.approvals:
        blockers.append("nobody has approved this image")
    if evidence.successful_loads < soak:
        blockers.append(
            f"only {evidence.successful_loads} clean loads on the bench, {soak} are wanted"
        )
    if evidence.failed_loads:
        blockers.append(f"{evidence.failed_loads} loads of this image have failed")
    if evidence.disagreements:
        blockers.append("two builds of this image disagree about their source")
    return blockers


def evaluate_promotion(
    record: ArtifactRecord,
    to_channel: str,
    evidence: PromotionEvidence | None = None,
    *,
    soak_loads: int = 3,
) -> PromotionDecision:
    """Decide whether an image may move to a channel, and say why not.

    Promotion is one step at a time on purpose. An image that has never run on
    the bench cannot go straight to golden however much evidence is attached
    to it, because the evidence for golden is meant to be evidence gathered in
    qual.
    """
    target = slug(to_channel)
    here = channel_rank(record.channel)
    there = channel_rank(target)
    facts = evidence or PromotionEvidence()
    blockers: list[str] = []
    if there == here:
        blockers.append(f"the image is already in {target}")
    elif there < here:
        # Demotion is always allowed. Somebody has found a problem and the
        # image needs to stop being used, which is not a thing to argue with.
        return PromotionDecision(
            digest=record.digest, from_channel=record.channel, to_channel=target
        )
    elif there - here > 1:
        blockers.append(
            f"promotion goes one channel at a time, {record.channel} to {target} skips one"
        )
    else:
        blockers.extend(_qual_blockers(record, facts))
        if target == "golden":
            blockers.extend(_golden_blockers(facts, soak=soak_loads))
    return PromotionDecision(
        digest=record.digest,
        from_channel=record.channel,
        to_channel=target,
        blockers=tuple(blockers),
    )
