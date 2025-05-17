import pytest

from fpga.bitstream.mcs import DATA, END_OF_FILE, EXTENDED_LINEAR, hex_record, write_mcs
from fpga.errors import ValidationError


def lines(text):
    return text.strip().splitlines()


def test_the_end_of_file_record_is_the_one_everybody_knows():
    assert hex_record(address=0, record_type=END_OF_FILE, payload=b"") == ":00000001FF"


def test_a_data_record_checksums_the_way_the_format_says():
    record = hex_record(address=0x0100, record_type=DATA, payload=bytes([0x01, 0x02, 0x03, 0x04]))
    assert record == ":0401000001020304F1"
    body = bytes.fromhex(record[1:])
    assert sum(body) % 256 == 0


def test_an_extended_linear_record_carries_the_upper_address():
    record = hex_record(
        address=0,
        record_type=EXTENDED_LINEAR,
        payload=(0x000F).to_bytes(2, "big"),
    )
    assert record == ":02000004000FEB"


@pytest.mark.parametrize(
    ("address", "record_type", "payload"),
    [(0x10000, DATA, b""), (0, 0x100, b""), (0, DATA, b"\x00" * 300)],
)
def test_records_that_do_not_fit_are_refused(address, record_type, payload):
    with pytest.raises(ValidationError):
        hex_record(address=address, record_type=record_type, payload=payload)


def test_a_small_image_writes_a_segment_a_data_record_and_an_end():
    text = write_mcs(bytes(range(8)))
    assert lines(text) == [
        ":020000040000FA",
        ":080000000001020304050607DC",
        ":00000001FF",
    ]


def test_the_first_record_sets_the_segment():
    first = lines(write_mcs(bytes(range(8))))[0]
    assert first.startswith(":02000004")


def test_the_last_record_ends_the_file():
    assert lines(write_mcs(bytes(range(8))))[-1] == ":00000001FF"


def test_records_are_the_length_that_was_asked_for():
    body = lines(write_mcs(bytes(range(64)), record_bytes=32))[1:-1]
    assert [int(line[1:3], 16) for line in body] == [32, 32]


def test_a_short_last_record_carries_what_is_left():
    body = lines(write_mcs(bytes(range(40)), record_bytes=16))[1:-1]
    assert [int(line[1:3], 16) for line in body] == [16, 16, 8]


def test_a_new_segment_record_appears_every_sixty_four_kilobytes():
    text = write_mcs(b"\xa5" * (0x10000 + 32))
    segments = [line for line in lines(text) if line.startswith(":02000004")]
    assert len(segments) == 2
    assert segments[1] == ":020000040001F9"


def test_no_data_record_straddles_a_segment_boundary():
    text = write_mcs(b"\xa5" * (0x10000 + 32), record_bytes=32)
    for line in lines(text):
        if line[7:9] != "00":
            continue
        address = int(line[3:7], 16)
        assert address + int(line[1:3], 16) <= 0x10000


def test_an_image_can_start_part_way_up_the_part():
    text = write_mcs(b"\x01\x02", start_address=0x400000)
    assert lines(text)[0] == ":020000040040BA"


def test_a_start_address_that_is_not_on_a_segment_boundary_is_refused():
    with pytest.raises(ValidationError):
        write_mcs(b"\x01", start_address=0x1234)


def test_a_negative_start_address_is_refused():
    with pytest.raises(ValidationError):
        write_mcs(b"\x01", start_address=-0x10000)


@pytest.mark.parametrize("record_bytes", [0, 256])
def test_a_nonsense_record_length_is_refused(record_bytes):
    with pytest.raises(ValidationError):
        write_mcs(b"\x01", record_bytes=record_bytes)


def test_an_empty_image_still_writes_a_terminated_file():
    assert lines(write_mcs(b"")) == [":00000001FF"]


def test_every_line_checksums():
    for line in lines(write_mcs(bytes(range(256)) * 3)):
        assert sum(bytes.fromhex(line[1:])) % 256 == 0
