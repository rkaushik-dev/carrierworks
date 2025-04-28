import pytest

from fpga.bitstream.bitfile import build_bitfile, parse_bitfile
from fpga.errors import ValidationError

BODY = bytes(range(256)) * 4


def sample(**changes):
    fields = {
        "design": "radar_frontend;UserID=0XFFFFFFFF;Version=2024.1",
        "part": "7a100tfgg484",
        "date": "2025/04/21",
        "time": "14:02:19",
        "data": BODY,
    }
    fields.update(changes)
    return build_bitfile(**fields)


def test_a_built_file_parses_back():
    parsed = parse_bitfile(sample())
    assert parsed.design_name == "radar_frontend"
    assert parsed.part == "7a100tfgg484"
    assert parsed.date == "2025/04/21"
    assert parsed.time == "14:02:19"
    assert parsed.data == BODY
    assert parsed.length == len(BODY)


def test_the_attributes_after_the_name_are_read():
    parsed = parse_bitfile(sample())
    assert parsed.tool_version == "2024.1"
    assert parsed.user_id == 0xFFFFFFFF
    assert parsed.attributes["userid"] == "0XFFFFFFFF"


def test_a_design_without_attributes_still_works():
    parsed = parse_bitfile(sample(design="beamformer"))
    assert parsed.design_name == "beamformer"
    assert parsed.attributes == {}
    assert parsed.tool_version is None
    assert parsed.user_id is None


def test_a_user_id_that_is_not_hex_is_reported_as_missing():
    parsed = parse_bitfile(sample(design="top;UserID=none"))
    assert parsed.user_id is None


@pytest.mark.parametrize(
    ("part", "device", "package"),
    [
        ("7a100tfgg484", "xc7a100t", "fgg484"),
        ("7a35tcpg236", "xc7a35t", "cpg236"),
        ("7k325tffg900", "xc7k325t", "ffg900"),
        ("7s25csga324", "xc7s25", "csga324"),
        ("7z020clg484", "xc7z020", "clg484"),
        ("xc7a200tfbg676", "xc7a200t", "fbg676"),
        ("ku040ffva1156", "xcku040", "ffva1156"),
    ],
)
def test_the_device_is_pulled_out_of_the_part_field(part, device, package):
    parsed = parse_bitfile(sample(part=part))
    assert parsed.device == device
    assert parsed.package == package


def test_a_part_field_that_makes_no_sense_is_refused():
    parsed = parse_bitfile(sample(part="whatever"))
    with pytest.raises(ValidationError):
        parsed.device


def test_a_file_that_is_not_a_bit_file_is_refused():
    with pytest.raises(ValidationError):
        parse_bitfile(b"\x00" * 64)


def test_a_file_that_is_too_short_is_refused():
    with pytest.raises(ValidationError):
        parse_bitfile(b"\x00\x09")


def test_a_truncated_header_is_refused():
    whole = sample()
    with pytest.raises(ValidationError):
        parse_bitfile(whole[:20])


def test_a_truncated_body_is_refused():
    whole = sample()
    with pytest.raises(ValidationError) as caught:
        parse_bitfile(whole[:-10])
    assert caught.value.details["present"] == len(BODY) - 10


def test_fields_out_of_order_are_refused():
    whole = bytearray(sample())
    # the b field tag sits right after the a field
    index = whole.index(b"b", 13)
    whole[index] = ord("c")
    with pytest.raises(ValidationError):
        parse_bitfile(bytes(whole))


def test_a_string_field_without_its_terminator_is_refused():
    whole = bytearray(sample(design="top"))
    index = whole.index(b"top")
    whole[index + 3] = ord("!")
    with pytest.raises(ValidationError):
        parse_bitfile(bytes(whole))


def test_an_empty_image_is_still_a_valid_file():
    parsed = parse_bitfile(sample(data=b""))
    assert parsed.length == 0
    assert parsed.data == b""


def test_a_field_longer_than_the_length_word_is_refused():
    with pytest.raises(ValidationError):
        build_bitfile(
            design="x" * 70000,
            part="7a100tfgg484",
            date="2025/04/21",
            time="14:02:19",
            data=b"",
        )


def test_the_header_is_the_length_everyone_expects():
    whole = sample(design="a", part="7a100tfgg484", date="d", time="t", data=b"")
    # thirteen bytes of preamble, four tagged strings, then the data field
    assert len(whole) == 13 + (1 + 2 + 2) + (1 + 2 + 13) + (1 + 2 + 2) * 2 + 5
