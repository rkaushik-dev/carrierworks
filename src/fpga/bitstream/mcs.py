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

from fpga.errors import ValidationError

__all__ = [
    "DATA",
    "END_OF_FILE",
    "EXTENDED_LINEAR",
    "hex_record",
    "mcs_records",
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
