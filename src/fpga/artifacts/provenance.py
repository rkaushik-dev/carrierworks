"""Where an image came from, recorded at the moment it arrives.

Provenance is separate from the artifact record because it is about the build
rather than about the bytes. Two builds can produce the same image from
different sources, which sounds impossible until somebody rebuilds a tag on a
different agent and gets a byte identical result, and the bench then has one
artifact with two provenances. That is a fact worth keeping rather than a
conflict to resolve.

The constraints digest is the field people forget and the one that explains the
most. A design that closes timing on one build and not on the next, from the
same source revision, has almost always had its constraints changed by
somebody who did not think of them as source.
"""

from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path

from fpga.errors import NotFound, ValidationError
from fpga.ids import design_name, slug

__all__ = [
    "Provenance",
    "ProvenanceLog",
]

_SHA = re.compile(r"^[0-9a-f]{7,40}$")
_FILE = "provenance.json"


@dataclass(frozen=True, slots=True)
class Provenance:
    """One build, as the build system described it."""

    digest: str
    design: str
    source_revision: str
    build_id: str
    built_at: str
    tool_version: str = ""
    constraints_digest: str = ""
    builder: str = ""
    branch: str = ""
    dirty: bool = False

    def __post_init__(self) -> None:
        object.__setattr__(self, "design", design_name(self.design))
        object.__setattr__(self, "source_revision", self.source_revision.strip().lower())
        if not _SHA.match(self.source_revision):
            raise ValidationError(
                "a source revision is a git object name",
                field="source_revision",
                given=self.source_revision,
            )
        if not self.build_id.strip():
            raise ValidationError("a build has an identifier", field="build_id")
        if not self.built_at.strip():
            raise ValidationError("a build has a time", field="built_at")
        if self.branch:
            object.__setattr__(self, "branch", slug(self.branch))

    @property
    def short_revision(self) -> str:
        return self.source_revision[:12]

    @property
    def reproducible(self) -> bool:
        """A build off a clean tree with its constraints recorded can be redone."""
        return not self.dirty and bool(self.constraints_digest)

    def same_source_as(self, other: Provenance) -> bool:
        """Whether two builds started from the same inputs.

        Revision and constraints both, because either one changing changes the
        result and only one of them is in version control at most sites.
        """
        return (
            self.source_revision == other.source_revision
            and self.constraints_digest == other.constraints_digest
        )

    def describe(self) -> str:
        state = "dirty" if self.dirty else "clean"
        where = f" on {self.branch}" if self.branch else ""
        return f"{self.design} from {self.short_revision}{where} ({state}) build {self.build_id}"

    def to_json(self) -> str:
        return json.dumps(asdict(self), indent=2, sort_keys=True)

    @classmethod
    def from_json(cls, text: str) -> Provenance:
        return cls(**json.loads(text))


class ProvenanceLog:
    """Every build that has produced a given image.

    Kept beside the image in the artifact store, one file holding a list,
    because the interesting question is always all of them rather than the
    latest one.
    """

    def __init__(self, root: Path | str) -> None:
        self.root = Path(root)

    def _path(self, digest: str) -> Path:
        if len(digest) < 4:
            raise ValidationError("that is not a digest", field="digest", given=digest)
        return self.root / digest[:2] / digest[2:] / _FILE

    def record(self, provenance: Provenance) -> tuple[Provenance, ...]:
        """Add a build, unless an identical one is already recorded."""
        path = self._path(provenance.digest)
        if not path.parent.is_dir():
            raise NotFound("artifact", provenance.digest)
        existing = self.for_digest(provenance.digest)
        if any(build == provenance for build in existing):
            return existing
        updated = (*existing, provenance)
        path.write_text(
            json.dumps([asdict(build) for build in updated], indent=2, sort_keys=True),
            encoding="utf-8",
        )
        return updated

    def for_digest(self, digest: str) -> tuple[Provenance, ...]:
        path = self._path(digest)
        if not path.is_file():
            return ()
        raw = json.loads(path.read_text(encoding="utf-8"))
        return tuple(Provenance(**entry) for entry in raw)

    def revisions(self, digest: str) -> tuple[str, ...]:
        """Every source revision that has produced this image."""
        return tuple(sorted({build.source_revision for build in self.for_digest(digest)}))

    def disagreements(self, digest: str) -> tuple[str, ...]:
        """Builds of this image whose inputs differ from the first one recorded.

        Not an error. It is how a rebuild on a different branch shows up, and
        the operator wants to see it rather than have it hidden.
        """
        builds = self.for_digest(digest)
        if len(builds) < 2:
            return ()
        first = builds[0]
        return tuple(build.build_id for build in builds[1:] if not build.same_source_as(first))
