"""Names, tags and serials, and the rules each of them has to keep.

The lab labels three kinds of thing and it labels them badly if left alone.
Boards get an asset tag off a printed sheet, which is the only identifier that
survives a board being reflowed and coming back with a new serial. Adapters get
whatever serial FTDI burned into them, which is opaque but unique. Designs get
a name from whoever wrote the RTL, and those arrive with spaces, capitals and
the occasional slash in them.

Sorting matters more than it looks. Asset tags print on labels in a rack and
people read them in order, so bx-9 has to come before bx-10 rather than after
it, which is what happens if anyone sorts the strings.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from fpga.errors import ValidationError

__all__ = [
    "AssetTag",
    "IdFactory",
    "adapter_serial",
    "design_name",
    "slug",
]

_TAG = re.compile(r"^(?P<prefix>[a-z]{2,4})-(?P<number>\d{1,5})$")
_SERIAL = re.compile(r"^[A-Z0-9]{6,16}$")
_DESIGN = re.compile(r"^[a-z][a-z0-9_]{2,63}$")
_SLUG = re.compile(r"^[a-z0-9][a-z0-9_-]{1,63}$")


@dataclass(frozen=True, slots=True, order=True)
class AssetTag:
    """The label stuck on the board, taken apart so it sorts properly."""

    prefix: str
    number: int

    def __post_init__(self) -> None:
        if not re.match(r"^[a-z]{2,4}$", self.prefix):
            raise ValidationError("bad asset tag prefix", field="prefix", given=self.prefix)
        if not 0 < self.number < 100000:
            raise ValidationError("asset tag number out of range", field="number")

    @classmethod
    def parse(cls, text: str) -> AssetTag:
        cleaned = text.strip().lower().replace(" ", "")
        match = _TAG.match(cleaned)
        if match is None:
            raise ValidationError("cannot read asset tag " + repr(text), field="asset_tag")
        return cls(prefix=match.group("prefix"), number=int(match.group("number")))

    def printed(self) -> str:
        """The form on the label, zero padded to four so the columns line up."""
        return f"{self.prefix.upper()}-{self.number:04d}"

    def next(self) -> AssetTag:
        return AssetTag(prefix=self.prefix, number=self.number + 1)

    def __str__(self) -> str:
        return f"{self.prefix}-{self.number}"


def adapter_serial(text: str) -> str:
    """A programmer serial, upper cased and checked for shape.

    FTDI and Digilent both burn alphanumeric strings and both are inconsistent
    about case when they report them, so the bench stores one form.
    """
    cleaned = text.strip().upper().replace(" ", "")
    if not _SERIAL.match(cleaned):
        raise ValidationError("not an adapter serial " + repr(text), field="serial")
    return cleaned


def design_name(text: str) -> str:
    """A design name as the build tools would accept it.

    Spaces and dashes become underscores because that is what the synthesis
    tool does anyway, and having the bench disagree with the tool about the
    name of a design is a whole afternoon of confusion.
    """
    cleaned = text.strip().lower()
    cleaned = re.sub(r"[\s\-/.]+", "_", cleaned)
    cleaned = re.sub(r"_{2,}", "_", cleaned).strip("_")
    if not _DESIGN.match(cleaned):
        raise ValidationError("not a usable design name " + repr(text), field="design")
    return cleaned


def slug(text: str) -> str:
    """A general identifier for teams, channels, racks and the like."""
    cleaned = re.sub(r"[\s_]+", "-", text.strip().lower())
    cleaned = re.sub(r"-{2,}", "-", cleaned).strip("-")
    if not _SLUG.match(cleaned):
        raise ValidationError("not a usable identifier " + repr(text), field="slug")
    return cleaned


class IdFactory:
    """Short ids for leases, jobs and runs.

    Sequential rather than random, and the prefix says what the thing is, so a
    job id read out over the bench is unambiguous. The counter is handed in so
    a restart can carry on from the database rather than replaying ids that
    already exist.
    """

    __slots__ = ("_counter", "_prefix", "_width")

    def __init__(self, prefix: str, start: int = 1, width: int = 5) -> None:
        self._prefix = slug(prefix)
        if start < 1:
            raise ValidationError("id counter starts at one", field="start", given=start)
        if not 3 <= width <= 9:
            raise ValidationError("id width is unreasonable", field="width", given=width)
        self._counter = start - 1
        self._width = width

    @property
    def issued(self) -> int:
        return self._counter

    def next(self) -> str:
        self._counter += 1
        if self._counter >= 10**self._width:
            raise ValidationError(
                "id space exhausted",
                field="counter",
                prefix=self._prefix,
                width=self._width,
            )
        return f"{self._prefix}-{self._counter:0{self._width}d}"

    def peek(self) -> str:
        """What the next id would be, without spending it."""
        return f"{self._prefix}-{self._counter + 1:0{self._width}d}"
