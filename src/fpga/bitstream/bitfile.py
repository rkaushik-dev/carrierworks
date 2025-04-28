"""The .bit wrapper: a handful of tagged fields and then the raw image.

The layout has not changed in twenty years. A two byte length, nine bytes of
padding, a two byte constant, and then four tagged strings and one tagged blob:
a for the design name, b for the part, c for the date, d for the time and e for
the configuration data with a four byte length. Strings carry their own null
terminator inside the declared length, which is the detail every home grown
parser gets wrong the first time.

The design field is not just a name. The tools append semicolon separated
attributes to it, so a real one reads like

    top_level;UserID=0XFFFFFFFF;Version=2024.1

and both halves matter. The name goes in the registry and the version says
which toolchain produced the image, which is the first thing anyone asks when
two builds of the same source behave differently.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from fpga.errors import ValidationError

__all__ = [
    "BitFile",
    "build_bitfile",
    "parse_bitfile",
]

_PREAMBLE = bytes([0x00, 0x09, 0x0F, 0xF0, 0x0F, 0xF0, 0x0F, 0xF0, 0x0F, 0xF0, 0x00, 0x00, 0x01])
_STRING_TAGS = ("a", "b", "c", "d")
_DATA_TAG = "e"

# The part field is the device and the package with nothing between them.
_PART = re.compile(
    r"^(?:xc)?(?P<device>\d[aksv]\d+t?|\dz\d+|[kv]u\d+p?)(?P<package>[a-z]+\d+)$",
)


@dataclass(frozen=True, slots=True)
class BitFile:
    """One parsed .bit file."""

    design: str
    part: str
    date: str
    time: str
    data: bytes

    @property
    def design_name(self) -> str:
        """The design name without the attributes the tools glue on."""
        return self.design.split(";", 1)[0].strip()

    @property
    def attributes(self) -> dict[str, str]:
        """Whatever came after the design name, as a mapping."""
        found: dict[str, str] = {}
        for chunk in self.design.split(";")[1:]:
            key, sep, value = chunk.partition("=")
            if sep:
                found[key.strip().lower()] = value.strip()
        return found

    @property
    def tool_version(self) -> str | None:
        return self.attributes.get("version")

    @property
    def user_id(self) -> int | None:
        raw = self.attributes.get("userid")
        if raw is None:
            return None
        try:
            return int(raw, 16)
        except ValueError:
            return None

    def _split_part(self) -> tuple[str, str]:
        raw = self.part.strip().lower()
        match = _PART.match(raw)
        if match is None:
            raise ValidationError("cannot read the part out of a bit file", field="part", given=raw)
        return "xc" + match.group("device"), match.group("package")

    @property
    def device(self) -> str:
        """The device code, with the package stripped off.

        The part field carries something like 7a100tfgg484, which is the
        device without its leading xc and with the package glued on the end.
        The bench works in full device codes, so this puts the xc back and
        cuts at the package.
        """
        return self._split_part()[0]

    @property
    def package(self) -> str:
        return self._split_part()[1]

    @property
    def length(self) -> int:
        return len(self.data)


def _read_string(data: bytes, offset: int) -> tuple[str, int]:
    if offset + 2 > len(data):
        raise ValidationError("bit file ended inside a field length", field="bitfile")
    length = int.from_bytes(data[offset : offset + 2], "big")
    offset += 2
    if offset + length > len(data):
        raise ValidationError("bit file ended inside a field", field="bitfile")
    raw = data[offset : offset + length]
    if not raw.endswith(b"\x00"):
        raise ValidationError("a bit file string field is null terminated", field="bitfile")
    return raw[:-1].decode("ascii", errors="replace"), offset + length


def parse_bitfile(data: bytes) -> BitFile:
    """Take a .bit file apart, header and image."""
    if len(data) < len(_PREAMBLE) + 8:
        raise ValidationError("too short to be a bit file", field="bitfile", length=len(data))
    if data[: len(_PREAMBLE)] != _PREAMBLE:
        raise ValidationError("that is not a bit file header", field="bitfile")
    offset = len(_PREAMBLE)
    fields: dict[str, str] = {}
    for tag in _STRING_TAGS:
        if offset >= len(data):
            raise ValidationError("bit file ended before its fields did", field="bitfile")
        found = chr(data[offset])
        if found != tag:
            raise ValidationError(
                "bit file fields are out of order",
                field="bitfile",
                expected=tag,
                given=found,
            )
        offset += 1
        fields[tag], offset = _read_string(data, offset)
    if offset >= len(data) or chr(data[offset]) != _DATA_TAG:
        raise ValidationError("bit file has no data field", field="bitfile")
    offset += 1
    if offset + 4 > len(data):
        raise ValidationError("bit file ended inside the data length", field="bitfile")
    length = int.from_bytes(data[offset : offset + 4], "big")
    offset += 4
    body = data[offset : offset + length]
    if len(body) != length:
        raise ValidationError(
            "bit file is shorter than its data length claims",
            field="bitfile",
            claimed=length,
            present=len(body),
        )
    return BitFile(
        design=fields["a"],
        part=fields["b"],
        date=fields["c"],
        time=fields["d"],
        data=body,
    )


def build_bitfile(
    *,
    design: str,
    part: str,
    date: str,
    time: str,
    data: bytes,
) -> bytes:
    """Write a .bit file, which is how the tests get one and how the bench
    hands a trimmed image back to somebody."""
    out = bytearray(_PREAMBLE)
    for tag, value in zip(_STRING_TAGS, (design, part, date, time), strict=True):
        encoded = value.encode("ascii") + b"\x00"
        if len(encoded) > 0xFFFF:
            raise ValidationError("bit file field is too long", field=tag)
        out += tag.encode("ascii")
        out += len(encoded).to_bytes(2, "big")
        out += encoded
    out += _DATA_TAG.encode("ascii")
    out += len(data).to_bytes(4, "big")
    out += data
    return bytes(out)
