"""Writing out everything the bench knows about one image, as files.

A bundle is what somebody hands to whoever is signing off a release: the image
itself, the reports that were read for it, where it came from, and every load
of it onto a board with what happened. The image goes in as the configuration
bytes rather than as the .bit container it arrived in, because those are the
bytes that were written to the part and the container header is metadata the
manifest already carries. Nothing in it is new information and
that is the point. It is the same rows, written to a directory that can be
attached to a ticket and read in two years by somebody with no access to this
bench at all.

The manifest lists every file with its sha256, and the manifest itself is
hashed into the result. That is not a signature and it is not pretending to
be: it is enough to notice a bundle that has been edited or truncated in
transit, which is the failure that actually happens.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

from fpga.artifacts.registry import ArtifactStore
from fpga.errors import NotFound, ValidationError
from fpga.persistence.unit import UnitOfWork

__all__ = [
    "Bundle",
    "assemble",
    "verify",
]

_MANIFEST = "manifest.json"


@dataclass(frozen=True, slots=True)
class Bundle:
    """One written bundle, and what went into it."""

    digest: str
    root: Path
    files: tuple[str, ...]
    manifest_digest: str
    jobs: int
    reports: int
    builds: int

    @property
    def manifest_path(self) -> Path:
        return self.root / _MANIFEST

    def describe(self) -> str:
        return (
            f"{self.digest[:12]} bundled to {self.root}: "
            f"{len(self.files)} files, {self.jobs} loads, {self.reports} reports"
        )


def _write(root: Path, name: str, payload: bytes | str) -> str:
    """Write one file and hand back its sha256."""
    path = root / name
    data = payload if isinstance(payload, bytes) else payload.encode("utf-8")
    path.write_bytes(data)
    return hashlib.sha256(data).hexdigest()


def _as_json(payload: object) -> str:
    return json.dumps(payload, indent=2, default=str, sort_keys=True) + "\n"


def assemble(
    unit: UnitOfWork,
    digest: str,
    into: Path | str,
    now: datetime,
    *,
    store: ArtifactStore,
    approvals: tuple[str, ...] = (),
) -> Bundle:
    """Write the bundle for one image into a directory of its own.

    The directory is named after the image rather than after the design,
    because two designs with the same name and different bytes is the exact
    situation a bundle is meant to settle.
    """
    record = unit.artifacts.get(digest)
    if not store.has(record.digest):
        raise NotFound("image bytes", record.digest)
    root = Path(into) / record.digest[:12]
    if root.exists() and any(root.iterdir()):
        raise ValidationError("that bundle directory is not empty", field="into", path=str(root))
    root.mkdir(parents=True, exist_ok=True)

    jobs = unit.jobs.for_digest(record.digest)
    report = unit.reports.for_digest(record.digest)
    builds = unit.provenance.for_digest(record.digest)

    hashes = {
        "image.bin": _write(root, "image.bin", store.read(record.digest)),
        "artifact.json": _write(root, "artifact.json", record.to_json()),
        "jobs.json": _write(
            root,
            "jobs.json",
            _as_json(
                [
                    {
                        "id": job.id,
                        "board": job.board,
                        "mode": job.mode,
                        "state": job.state,
                        "attempts": job.attempts,
                        "requested_by": job.requested_by,
                        "error": job.error,
                        "error_code": job.error_code,
                    }
                    for job in jobs
                ]
            ),
        ),
        "provenance.json": _write(
            root,
            "provenance.json",
            _as_json([json.loads(build.to_json()) for build in builds]),
        ),
    }
    if report is not None:
        hashes["reports.json"] = _write(
            root,
            "reports.json",
            _as_json(
                {
                    "design": report.design,
                    "read_at": report.at,
                    "revision": report.revision,
                    "closed": report.closed,
                    "timing": report.timing.describe(),
                    "worst_slack_ps": report.timing.wns_ps,
                    "utilization": report.utilization.describe(),
                }
            ),
        )

    manifest = _as_json(
        {
            "digest": record.digest,
            "design": record.design,
            "device": record.device,
            "channel": record.channel,
            "written_at": now,
            "approvals": sorted(set(approvals)),
            "files": dict(sorted(hashes.items())),
        }
    )
    manifest_digest = _write(root, _MANIFEST, manifest)
    return Bundle(
        digest=record.digest,
        root=root,
        files=tuple(sorted([*hashes, _MANIFEST])),
        manifest_digest=manifest_digest,
        jobs=len(jobs),
        reports=0 if report is None else 1,
        builds=len(builds),
    )


def verify(root: Path | str) -> tuple[str, ...]:
    """Every file in a bundle that does not match its manifest entry.

    A missing file and a changed file are both reported, in that order, so a
    truncated copy reads as clearly as an edited one.
    """
    folder = Path(root)
    manifest_path = folder / _MANIFEST
    if not manifest_path.is_file():
        raise NotFound("bundle manifest", str(folder))
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    problems: list[str] = []
    for name, expected in sorted(manifest.get("files", {}).items()):
        path = folder / name
        if not path.is_file():
            problems.append(f"{name} is missing")
            continue
        found = hashlib.sha256(path.read_bytes()).hexdigest()
        if found != expected:
            problems.append(f"{name} does not match the manifest")
    return tuple(problems)
