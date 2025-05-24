"""A content addressed store for images, and the records that describe them.

Addressing by digest settles three arguments at once. Two builds that produce
the same image are one entry rather than two, so the registry does not grow a
copy every night for a design nobody has touched. An image cannot be quietly
replaced, because changing it changes where it lives. And the digest on a job
record is enough to fetch exactly what was loaded, months later, without
trusting a file name.

The store keeps the raw configuration data, not the .bit file it arrived in.
The header is a wrapper, the tools regenerate it, and two files whose headers
differ only by a timestamp are not two artifacts.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, replace
from datetime import UTC, datetime
from pathlib import Path

from fpga.bitstream.image import Bitstream
from fpga.errors import NotFound, ValidationError
from fpga.ids import design_name, slug

__all__ = [
    "ArtifactRecord",
    "ArtifactStore",
]

_META = "meta.json"
_IMAGE = "image.bin"


@dataclass(frozen=True, slots=True)
class ArtifactRecord:
    """What the bench knows about one stored image."""

    digest: str
    design: str
    device: str
    length: int
    kind: str
    channel: str = "dev"
    source: str = ""
    tool_version: str = ""
    stored_at: str = ""
    tags: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if len(self.digest) != 64 or any(c not in "0123456789abcdef" for c in self.digest):
            raise ValidationError("a digest is sixty four hex characters", field="digest")
        object.__setattr__(self, "design", design_name(self.design))
        object.__setattr__(self, "channel", slug(self.channel))
        object.__setattr__(self, "tags", tuple(sorted({slug(tag) for tag in self.tags})))
        if self.kind not in ("full", "partial"):
            raise ValidationError("an image is full or partial", field="kind", given=self.kind)
        if self.length <= 0:
            raise ValidationError("an artifact has some bytes", field="length")

    @property
    def short_digest(self) -> str:
        return self.digest[:12]

    @property
    def is_partial(self) -> bool:
        return self.kind == "partial"

    def with_channel(self, channel: str) -> ArtifactRecord:
        return replace(self, channel=channel)

    def tagged(self, *tags: str) -> ArtifactRecord:
        return replace(self, tags=(*self.tags, *tags))

    def describe(self) -> str:
        return f"{self.design} {self.short_digest} {self.kind} {self.device} [{self.channel}]"

    def to_json(self) -> str:
        return json.dumps(asdict(self), indent=2, sort_keys=True)

    @classmethod
    def from_json(cls, text: str) -> ArtifactRecord:
        raw = json.loads(text)
        raw["tags"] = tuple(raw.get("tags", ()))
        return cls(**raw)

    @classmethod
    def of(cls, image: Bitstream, *, channel: str = "dev", stored_at: str = "") -> ArtifactRecord:
        return cls(
            digest=image.digest,
            design=image.design,
            device=image.device,
            length=image.length,
            kind="partial" if image.is_partial else "full",
            channel=channel,
            source=image.source,
            tool_version=image.header.tool_version or "",
            stored_at=stored_at,
        )


class ArtifactStore:
    """Images on disk, addressed by digest."""

    def __init__(self, root: Path | str) -> None:
        self.root = Path(root)

    def _folder(self, digest: str) -> Path:
        if len(digest) < 4:
            raise ValidationError("that is not a digest", field="digest", given=digest)
        return self.root / digest[:2] / digest[2:]

    def path_for(self, digest: str) -> Path:
        """Where the image bytes for a digest live, whether or not they do."""
        return self._folder(digest) / _IMAGE

    def has(self, digest: str) -> bool:
        return self.path_for(digest).is_file()

    def put(self, image: Bitstream, *, channel: str = "dev") -> ArtifactRecord:
        """Store an image, or hand back the record for the one already there.

        Storing the same image twice is normal. A nightly build of an
        unchanged design produces the same bytes, and the second store is a
        no operation rather than an error, so nobody has to check first.
        """
        record = ArtifactRecord.of(
            image,
            channel=channel,
            stored_at=datetime.now(UTC).isoformat(timespec="seconds"),
        )
        folder = self._folder(record.digest)
        if self.has(record.digest):
            return self.record_for(record.digest)
        folder.mkdir(parents=True, exist_ok=True)
        (folder / _IMAGE).write_bytes(image.header.data)
        (folder / _META).write_text(record.to_json(), encoding="utf-8")
        return record

    def record_for(self, digest: str) -> ArtifactRecord:
        meta = self._folder(digest) / _META
        if not meta.is_file():
            raise NotFound("artifact", digest)
        return ArtifactRecord.from_json(meta.read_text(encoding="utf-8"))

    def read(self, digest: str) -> bytes:
        path = self.path_for(digest)
        if not path.is_file():
            raise NotFound("artifact", digest)
        return path.read_bytes()

    def update(self, record: ArtifactRecord) -> ArtifactRecord:
        """Write a changed record back, leaving the image alone."""
        if not self.has(record.digest):
            raise NotFound("artifact", record.digest)
        (self._folder(record.digest) / _META).write_text(record.to_json(), encoding="utf-8")
        return record

    def records(self) -> tuple[ArtifactRecord, ...]:
        """Every record in the store, newest first."""
        found = [
            ArtifactRecord.from_json(meta.read_text(encoding="utf-8"))
            for meta in sorted(self.root.glob(f"*/*/{_META}"))
        ]
        return tuple(
            sorted(found, key=lambda record: (record.stored_at, record.digest), reverse=True)
        )

    def for_design(self, design: str) -> tuple[ArtifactRecord, ...]:
        wanted = design_name(design)
        return tuple(record for record in self.records() if record.design == wanted)

    def remove(self, digest: str) -> None:
        folder = self._folder(digest)
        if not folder.is_dir():
            raise NotFound("artifact", digest)
        for child in folder.iterdir():
            child.unlink()
        folder.rmdir()
