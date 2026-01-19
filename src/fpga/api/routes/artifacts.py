"""The artifact routes: what images the bench holds and which channel they are in."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from fastapi import APIRouter, Body, Depends, Query, Request

from fpga.access import ApiKey
from fpga.api.deps import get_settings, now, unit_of_work
from fpga.api.security import needs
from fpga.artifacts.provenance import Provenance
from fpga.artifacts.registry import ArtifactStore
from fpga.bitstream.image import read_bitstream
from fpga.config import BenchSettings
from fpga.persistence.unit import UnitOfWork
from fpga.services import registry

router = APIRouter(prefix="/artifacts", tags=["artifacts"])


def artifact_store(
    request: Request,
    settings: BenchSettings = Depends(get_settings),
) -> ArtifactStore:
    """The store on disk, held on the app so a test can point it elsewhere."""
    store = getattr(request.app.state, "artifacts", None)
    if store is None:
        store = ArtifactStore(settings.artifact_root)
        request.app.state.artifacts = store
    return store


def _out(record: Any) -> dict[str, Any]:
    return {
        "digest": record.digest,
        "design": record.design,
        "device": record.device,
        "length": record.length,
        "kind": record.kind,
        "channel": record.channel,
        "tool_version": record.tool_version,
        "tags": list(record.tags),
        "stored_at": record.stored_at,
    }


@router.get("")
def list_artifacts(
    unit: UnitOfWork = Depends(unit_of_work),
    design: str | None = Query(default=None),
    channel: str | None = Query(default=None),
    _: ApiKey = Depends(needs("reader")),
) -> list[dict[str, Any]]:
    """Images, narrowed by design or by channel."""
    if design is not None:
        found = unit.artifacts.for_design(design)
    elif channel is not None:
        found = unit.artifacts.in_channel(channel)
    else:
        found = unit.artifacts.in_channel("dev") + unit.artifacts.in_channel("qual")
        found = found + unit.artifacts.in_channel("golden")
    return [_out(record) for record in found]


@router.get("/{digest}")
def read_artifact(
    digest: str,
    unit: UnitOfWork = Depends(unit_of_work),
    _: ApiKey = Depends(needs("reader")),
) -> dict[str, Any]:
    """One image's record."""
    return _out(unit.artifacts.get(digest))


@router.post("", status_code=201)
def upload_artifact(
    body: bytes = Body(media_type="application/octet-stream"),
    unit: UnitOfWork = Depends(unit_of_work),
    store: ArtifactStore = Depends(artifact_store),
    moment: datetime = Depends(now),
    channel: str = Query(default="dev"),
    _: ApiKey = Depends(needs("operator")),
) -> dict[str, Any]:
    """Take a bit file in, store it, and record what it is."""
    image = read_bitstream(body, source="upload")
    record = registry.ingest(unit, store, image, moment, channel=channel)
    return _out(record)


@router.post("/{digest}/builds", status_code=201)
def record_build(
    digest: str,
    build_id: str = Body(embed=True),
    source_revision: str = Body(embed=True),
    built_at: str = Body(embed=True),
    constraints_digest: str = Body(default="", embed=True),
    branch: str = Body(default="", embed=True),
    builder: str = Body(default="", embed=True),
    dirty: bool = Body(default=False, embed=True),
    unit: UnitOfWork = Depends(unit_of_work),
    _: ApiKey = Depends(needs("operator")),
) -> dict[str, Any]:
    """Record the build that produced an image."""
    record = unit.artifacts.get(digest)
    build = Provenance(
        digest=digest,
        design=record.design,
        source_revision=source_revision,
        build_id=build_id,
        built_at=built_at,
        constraints_digest=constraints_digest,
        branch=branch,
        builder=builder,
        dirty=dirty,
    )
    registry.record_build(unit, build)
    return {"digest": digest, "build_id": build.build_id, "reproducible": build.reproducible}


@router.post("/{digest}/promote")
def promote_artifact(
    digest: str,
    unit: UnitOfWork = Depends(unit_of_work),
    channel: str = Body(embed=True),
    approvals: list[str] = Body(default_factory=list, embed=True),
    timing_closed: bool | None = Body(default=None, embed=True),
    _: ApiKey = Depends(needs("admin")),
) -> dict[str, Any]:
    """Move an image up a channel, if everything the rules ask for is true."""
    evidence = registry.evidence_from(
        unit,
        digest,
        timing_closed=timing_closed,
        approvals=tuple(approvals),
    )
    decision = registry.promote(unit, digest, channel, evidence)
    return {"digest": digest, "channel": decision.to_channel, "moved": decision.allowed}


@router.get("/{digest}/loads")
def read_loads(
    digest: str,
    unit: UnitOfWork = Depends(unit_of_work),
    _: ApiKey = Depends(needs("reader")),
) -> dict[str, int]:
    """How many times this image has been loaded, and how often that failed."""
    unit.artifacts.get(digest)
    passed, failed = registry.counted_loads(unit, digest)
    return {"passed": passed, "failed": failed}
