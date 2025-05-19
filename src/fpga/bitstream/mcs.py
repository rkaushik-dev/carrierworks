"""Writing the Intel hex form the flash programmers eat.

An .mcs file is Intel hex with nothing exotic in it: data records, an extended
linear address record every sixty four kilobytes because the address field is
only sixteen bits wide, and an end of file record. The checksum is the two's
complement of the sum of every byte in the record, which catches a corrupted
line and nothing else, but it is what the format says and a programmer that
reads one will refuse a file without it.

Record length is sixteen bytes because that is what the vendor tool writes and
because a sixteen megabyte image at sixteen bytes a line is a file people can
still open in an editor when something has gone wrong. Thirty two is allowed
and halves the size; anything else works and looks unfamiliar, which costs more
than it saves.
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass

from fpga.errors import ValidationError

__all__ = [
    "DATA",
    "END_OF_FILE",
    "EXTENDED_LINEAR",
    "HexRegion",
    "flatten",
    "hex_record",
    "mcs_records",
    "read_mcs",
    "write_mcs",
]

DATA = 0x00
END_OF_FILE = 0x01
EXTENDED_LINEAR = 0x04

_SEGMENT = 0x10000


def hex_record(*, address: int, record_type: int, payload: bytes) -> str:
    """One Intel hex line, checksum and all."""
    if not 0 <= address <= 0xFFFF:
        raise ValidationError("a hex record address is sixteen bits", field="address")
    if not 0 <= record_type <= 0xFF:
        raise ValidationError("a record type is one byte", field="record_type")
    if len(payload) > 0xFF:
        raise ValidationError("a hex record holds at most 255 bytes", field="payload")
    body = bytes([len(payload), (address >> 8) & 0xFF, address & 0xFF, record_type]) + payload
    checksum = (-sum(body)) & 0xFF
    return ":" + (body + bytes([checksum])).hex().upper()


def mcs_records(
    data: bytes,
    *,
    start_address: int = 0,
    record_bytes: int = 16,
) -> Iterator[str]:
    """Every record for an image, in order, without building the file."""
    if not 1 <= record_bytes <= 0xFF:
        raise ValidationError("record length is out of range", field="record_bytes")
    if start_address < 0:
        raise ValidationError("a start address is not negative", field="start_address")
    if start_address % _SEGMENT:
        raise ValidationError(
            "an mcs image starts on a sixty four kilobyte boundary",
            field="start_address",
            given=start_address,
        )
    if (start_address + len(data)) > 0x1_0000_0000:
        raise ValidationError("that image does not fit in a thirty two bit address space")
    segment = -1
    offset = 0
    while offset < len(data):
        address = start_address + offset
        if address // _SEGMENT != segment:
            segment = address // _SEGMENT
            yield hex_record(
                address=0,
                record_type=EXTENDED_LINEAR,
                payload=segment.to_bytes(2, "big"),
            )
        room = _SEGMENT - (address % _SEGMENT)
        take = min(record_bytes, room, len(data) - offset)
        yield hex_record(
            address=address % _SEGMENT,
            record_type=DATA,
            payload=data[offset : offset + take],
        )
        offset += take
    yield hex_record(address=0, record_type=END_OF_FILE, payload=b"")


def write_mcs(data: bytes, *, start_address: int = 0, record_bytes: int = 16) -> str:
    """The whole file as text, newline terminated the way the tools write it."""
    return "".join(
        record + "\n"
        for record in mcs_records(
            data,
            start_address=start_address,
            record_bytes=record_bytes,
        )
    )


@dataclass(frozen=True, slots=True)
class HexRegion:
    """A run of bytes that were contiguous in the file."""

    start: int
    data: bytes

    @property
    def end(self) -> int:
        """One past the last byte, the way a slice reads."""
        return self.start + len(self.data)

    @property
    def length(self) -> int:
        return len(self.data)

    def describe(self) -> str:
        return f"0x{self.start:08x}..0x{self.end:08x} ({self.length} bytes)"


def read_mcs(text: str) -> tuple[HexRegion, ...]:
    """Read a file back into the runs of bytes it holds.

    A multiboot file has a hole in the middle, so the answer is a list of
    regions rather than one image. Joining them back together is the caller's
    decision because it needs a fill value and the caller knows which one.
    """
    regions: list[tuple[int, bytearray]] = []
    segment = 0
    ended = False
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line:
            continue
        if ended:
            raise ValidationError("records after the end of file record", field="mcs", line=number)
        if not line.startswith(":"):
            raise ValidationError("a hex record starts with a colon", field="mcs", line=number)
        try:
            body = bytes.fromhex(line[1:])
        except ValueError as exc:
            raise ValidationError("a hex record is hex", field="mcs", line=number) from exc
        if len(body) < 5:
            raise ValidationError("a hex record is at least five bytes", field="mcs", line=number)
        if sum(body) % 256:
            raise ValidationError("a hex record failed its checksum", field="mcs", line=number)
        count, address, record_type = body[0], int.from_bytes(body[1:3], "big"), body[3]
        payload = body[4:-1]
        if len(payload) != count:
            raise ValidationError("a hex record lied about its length", field="mcs", line=number)
        if record_type == END_OF_FILE:
            ended = True
        elif record_type == EXTENDED_LINEAR:
            if count != 2:
                raise ValidationError(
                    "a segment record carries two bytes",
                    field="mcs",
                    line=number,
                )
            segment = int.from_bytes(payload, "big")
        elif record_type == DATA:
            start = segment * _SEGMENT + address
            if regions and regions[-1][0] + len(regions[-1][1]) == start:
                regions[-1][1].extend(payload)
            else:
                regions.append((start, bytearray(payload)))
        else:
            raise ValidationError(
                "unsupported hex record type",
                field="mcs",
                line=number,
                record_type=record_type,
            )
    if not ended:
        raise ValidationError("the file has no end of file record", field="mcs")
    return tuple(HexRegion(start=start, data=bytes(data)) for start, data in regions)


def flatten(regions: tuple[HexRegion, ...], fill: int = 0xFF) -> tuple[int, bytes]:
    """Join regions into one image, filling the holes.

    Returns the address the image starts at along with the bytes, because a
    file that begins part way up a part is normal and assuming zero would put
    every later address out by the offset.
    """
    if not regions:
        return (0, b"")
    if not 0 <= fill <= 0xFF:
        raise ValidationError("the fill is one byte", field="fill", given=fill)
    ordered = sorted(regions, key=lambda region: region.start)
    start = ordered[0].start
    out = bytearray()
    for region in ordered:
        offset = region.start - start
        if offset < len(out):
            raise ValidationError(
                "two regions overlap",
                field="regions",
                at=f"0x{region.start:08x}",
            )
        out.extend(bytes([fill]) * (offset - len(out)))
        out.extend(region.data)
    return (start, bytes(out))
