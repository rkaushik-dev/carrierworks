import pytest

from fpga.bitstream.mcs import flatten, hex_record, read_mcs, write_mcs
from fpga.errors import ValidationError

IMAGE = bytes(range(256)) * 3


def test_a_written_file_reads_back_as_one_region():
    regions = read_mcs(write_mcs(IMAGE))
    assert len(regions) == 1
    assert regions[0].start == 0
    assert regions[0].data == IMAGE


def test_a_file_that_starts_part_way_up_keeps_its_address():
    regions = read_mcs(write_mcs(IMAGE, start_address=0x400000))
    assert regions[0].start == 0x400000
    assert regions[0].end == 0x400000 + len(IMAGE)
    assert regions[0].length == len(IMAGE)


def test_a_region_describes_itself_in_hex():
    region = read_mcs(write_mcs(b"\x01\x02", start_address=0x10000))[0]
    assert region.describe() == "0x00010000..0x00010002 (2 bytes)"


def test_records_across_a_segment_boundary_stay_one_region():
    image = b"\xa5" * (0x10000 + 64)
    regions = read_mcs(write_mcs(image))
    assert len(regions) == 1
    assert regions[0].data == image


def test_two_images_in_one_file_come_back_as_two_regions():
    golden = write_mcs(b"\x11" * 32).strip().splitlines()[:-1]
    update = write_mcs(b"\x22" * 32, start_address=0x400000).strip().splitlines()
    regions = read_mcs("\n".join(golden + update))
    assert [region.start for region in regions] == [0, 0x400000]
    assert regions[1].data == b"\x22" * 32


def test_flattening_fills_the_hole_between_regions():
    golden = write_mcs(b"\x11" * 32).strip().splitlines()[:-1]
    update = write_mcs(b"\x22" * 32, start_address=0x10000).strip().splitlines()
    start, image = flatten(read_mcs("\n".join(golden + update)))
    assert start == 0
    assert len(image) == 0x10000 + 32
    assert image[32:0x10000] == b"\xff" * (0x10000 - 32)


def test_flattening_reports_where_the_image_actually_starts():
    start, image = flatten(read_mcs(write_mcs(b"\x01\x02", start_address=0x400000)))
    assert start == 0x400000
    assert image == b"\x01\x02"


def test_flattening_nothing_gives_nothing():
    assert flatten(()) == (0, b"")


def test_overlapping_regions_are_refused():
    from fpga.bitstream.mcs import HexRegion

    with pytest.raises(ValidationError):
        flatten((HexRegion(0, b"\x01\x02\x03"), HexRegion(2, b"\x04")))


def test_a_bad_fill_is_refused():
    from fpga.bitstream.mcs import HexRegion

    with pytest.raises(ValidationError):
        flatten((HexRegion(0, b"\x01"),), fill=999)


def test_blank_lines_are_ignored():
    text = "\n\n".join(write_mcs(b"\x01\x02").strip().splitlines())
    assert read_mcs(text)[0].data == b"\x01\x02"


def test_a_file_without_an_end_record_is_refused():
    text = "\n".join(write_mcs(IMAGE).strip().splitlines()[:-1])
    with pytest.raises(ValidationError):
        read_mcs(text)


def test_records_after_the_end_record_are_refused():
    text = write_mcs(b"\x01") + hex_record(address=0, record_type=0, payload=b"\x02") + "\n"
    with pytest.raises(ValidationError):
        read_mcs(text)


def test_a_line_that_is_not_a_record_is_refused():
    with pytest.raises(ValidationError):
        read_mcs("hello\n:00000001FF\n")


def test_a_record_that_is_not_hex_is_refused():
    with pytest.raises(ValidationError):
        read_mcs(":zz00000102\n:00000001FF\n")


def test_a_record_that_is_too_short_is_refused():
    with pytest.raises(ValidationError):
        read_mcs(":0000\n:00000001FF\n")


def test_a_broken_checksum_is_refused():
    good = write_mcs(b"\x01\x02").strip().splitlines()
    broken = good[1][:-2] + "00"
    with pytest.raises(ValidationError) as caught:
        read_mcs("\n".join([good[0], broken, good[2]]))
    assert caught.value.details["line"] == 2


def test_a_record_that_lies_about_its_length_is_refused():
    # count says four, payload holds two, and the checksum is made to agree
    body = bytes([0x04, 0x00, 0x00, 0x00, 0x01, 0x02])
    line = ":" + (body + bytes([(-sum(body)) & 0xFF])).hex().upper()
    with pytest.raises(ValidationError):
        read_mcs(line + "\n:00000001FF\n")


def test_an_unsupported_record_type_is_refused():
    line = hex_record(address=0, record_type=0x03, payload=b"\x00\x00\x00\x00")
    with pytest.raises(ValidationError):
        read_mcs(line + "\n:00000001FF\n")


def test_a_segment_record_of_the_wrong_length_is_refused():
    line = hex_record(address=0, record_type=0x04, payload=b"\x00")
    with pytest.raises(ValidationError):
        read_mcs(line + "\n:00000001FF\n")


def test_a_big_image_round_trips_byte_for_byte():
    image = bytes(range(256)) * 400
    start, read_back = flatten(read_mcs(write_mcs(image, record_bytes=32)))
    assert start == 0
    assert read_back == image
