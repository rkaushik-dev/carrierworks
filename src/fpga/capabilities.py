"""What a board can do, and how a job says what it needs.

Requirements are written as short strings because they come from three places
that all have to write the same thing: a command line, a yaml file in a
regression repo, and a text column in the database. Anything richer than a
string would be three parsers instead of one.

The grammar is small on purpose. A bare token asks for a capability, a token
with a bang in front asks for its absence, a comparison asks about a number,
and an equals asks about an attribute. That covers every request the lab has
actually made, and the one time somebody wanted a boolean or, the honest answer
was to book two boards.

The explanations matter as much as the answers. A scheduler that says no
without saying why sends people to look at the rack, and the walk to the rack
is five minutes each way.
"""

from __future__ import annotations

import re
from collections.abc import Iterator
from dataclasses import dataclass, field

from fpga.errors import ValidationError
from fpga.ids import slug

__all__ = [
    "CapabilityProfile",
    "Requirement",
    "RequirementSet",
]

_COMPARISON = re.compile(r"^(?P<key>[a-z][a-z0-9_-]*)\s*(?P<op>>=|<=|!=|=|>|<)\s*(?P<value>\S+)$")


@dataclass(frozen=True, slots=True)
class CapabilityProfile:
    """Everything true about one board, in the form requirements ask about."""

    tokens: frozenset[str] = field(default_factory=frozenset)
    numbers: dict[str, int] = field(default_factory=dict)
    attributes: dict[str, str] = field(default_factory=dict)

    @classmethod
    def build(
        cls,
        tokens: set[str] | frozenset[str] | None = None,
        numbers: dict[str, int] | None = None,
        attributes: dict[str, str] | None = None,
    ) -> CapabilityProfile:
        return cls(
            tokens=frozenset(slug(token) for token in tokens or ()),
            numbers={slug(key): int(value) for key, value in (numbers or {}).items()},
            attributes={slug(key): str(value).lower() for key, value in (attributes or {}).items()},
        )

    def with_tokens(self, extra: set[str] | frozenset[str]) -> CapabilityProfile:
        """The same profile with a card's capabilities folded in."""
        return CapabilityProfile(
            tokens=self.tokens | frozenset(slug(token) for token in extra),
            numbers=dict(self.numbers),
            attributes=dict(self.attributes),
        )

    def number(self, key: str) -> int:
        """A count, defaulting to none of them rather than to missing."""
        return self.numbers.get(key, 0)

    def attribute(self, key: str) -> str | None:
        return self.attributes.get(key)


@dataclass(frozen=True, slots=True)
class Requirement:
    """One thing a job asks of a board."""

    text: str
    kind: str
    key: str
    op: str = ""
    value: str = ""

    @classmethod
    def parse(cls, text: str) -> Requirement:
        cleaned = text.strip().lower()
        if not cleaned:
            raise ValidationError("empty requirement", field="requirement")
        if cleaned.startswith("!"):
            return cls(text=cleaned, kind="absent", key=slug(cleaned[1:]))
        match = _COMPARISON.match(cleaned)
        if match is None:
            return cls(text=cleaned, kind="present", key=slug(cleaned))
        key = slug(match.group("key"))
        op = match.group("op")
        raw = match.group("value")
        if op in (">=", "<=", ">", "<"):
            if not re.match(r"^-?\d+$", raw):
                raise ValidationError(
                    "a comparison needs a whole number",
                    field="requirement",
                    given=text,
                )
            return cls(text=cleaned, kind="number", key=key, op=op, value=raw)
        return cls(text=cleaned, kind="attribute", key=key, op=op, value=raw)

    def satisfied_by(self, profile: CapabilityProfile) -> bool:
        return self.explain(profile) is None

    def explain(self, profile: CapabilityProfile) -> str | None:
        """Nothing when it is met, otherwise why it is not."""
        if self.kind == "present":
            if self.key in profile.tokens:
                return None
            return f"no {self.key}"
        if self.kind == "absent":
            if self.key not in profile.tokens:
                return None
            return f"has {self.key} and the job asked for a board without it"
        if self.kind == "number":
            have = profile.number(self.key)
            want = int(self.value)
            if _compare(have, self.op, want):
                return None
            return f"{self.key} is {have}, wanted {self.op}{want}"
        have_attribute = profile.attribute(self.key)
        if self.op == "=":
            if have_attribute == self.value:
                return None
            found = have_attribute if have_attribute is not None else "nothing"
            return f"{self.key} is {found}, wanted {self.value}"
        if have_attribute != self.value:
            return None
        return f"{self.key} is {self.value} and the job asked for anything else"

    def __str__(self) -> str:
        return self.text


def _compare(have: int, op: str, want: int) -> bool:
    if op == ">=":
        return have >= want
    if op == "<=":
        return have <= want
    if op == ">":
        return have > want
    return have < want


@dataclass(frozen=True, slots=True)
class RequirementSet:
    """Everything a job asks for, all of which has to hold at once."""

    requirements: tuple[Requirement, ...] = ()

    @classmethod
    def parse(cls, texts: list[str] | tuple[str, ...] | str) -> RequirementSet:
        if isinstance(texts, str):
            parts = [part for part in re.split(r"[,\n]", texts) if part.strip()]
        else:
            parts = list(texts)
        return cls(tuple(Requirement.parse(part) for part in parts))

    def unmet(self, profile: CapabilityProfile) -> tuple[Requirement, ...]:
        return tuple(req for req in self.requirements if not req.satisfied_by(profile))

    def satisfied_by(self, profile: CapabilityProfile) -> bool:
        return not self.unmet(profile)

    def why_not(self, profile: CapabilityProfile) -> str:
        """One line an operator can read, listing every unmet requirement."""
        reasons = [req.explain(profile) for req in self.unmet(profile)]
        return "; ".join(reason for reason in reasons if reason)

    def __len__(self) -> int:
        return len(self.requirements)

    def __iter__(self) -> Iterator[Requirement]:
        return iter(self.requirements)
