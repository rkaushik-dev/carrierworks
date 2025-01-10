import pytest

from fpga.catalog.partnumber import PartNumber, parse_part_number
from fpga.errors import ValidationError


def test_seven_series_artix_comes_apart():
    part = parse_part_number("XC7A100T-2FGG484I")
    assert part.device == "xc7a100t"
    assert part.speed_grade == "2"
    assert part.package == "fgg484"
    assert part.temperature == "i"
    assert part.family == "artix7"
    assert part.pin_count == 484


def test_parsing_tolerates_lower_case_and_stray_spaces():
    assert parse_part_number("  xc7k325t-2ffg900c ") == parse_part_number("XC7K325T-2FFG900C")


@pytest.mark.parametrize(
    ("code", "family"),
    [
        ("XC7A35T-1CPG236C", "artix7"),
        ("XC7K70T-1FBG484C", "kintex7"),
        ("XC7V585T-2FFG1157C", "virtex7"),
        ("XC7S25-1CSGA324C", "spartan7"),
        ("XC7Z020-1CLG484C", "zynq7000"),
        ("XCKU040-2FFVA1156E", "kintex_ultrascale"),
        ("XCVU095-2FFVC2104E", "virtex_ultrascale"),
        ("XCZU9EG-2FFVB1156I", "zynq_ultrascale"),
        ("XC6SLX45-3CSG324C", "spartan6"),
    ],
)
def test_families_land_where_they_should(code, family):
    assert parse_part_number(code).family == family


def test_ordering_code_round_trips_in_upper_case():
    assert parse_part_number("xc7a200t-3fbg676c").ordering_code() == "XC7A200T-3FBG676C"
    assert str(parse_part_number("xc7a200t-3fbg676c")) == "XC7A200T-3FBG676C"


def test_low_power_screen_is_visible():
    part = parse_part_number("XC7A100T-1LFGG484I")
    assert part.low_power is True
    assert part.speed_grade == "1l"
    assert parse_part_number("XC7A100T-1FGG484I").low_power is False


def test_speed_rank_puts_low_power_between_grades():
    slow = parse_part_number("XC7A100T-1FGG484I")
    low_power_two = parse_part_number("XC7A100T-2LFGG484I")
    fast = parse_part_number("XC7A100T-2FGG484I")
    assert slow.speed_rank < low_power_two.speed_rank < fast.speed_rank


def test_a_faster_part_stands_in_for_a_slower_one():
    fast = parse_part_number("XC7A100T-3FGG484I")
    slow = parse_part_number("XC7A100T-1FGG484I")
    assert fast.fits(slow) is True
    assert slow.fits(fast) is False


def test_a_different_package_never_stands_in():
    assert not parse_part_number("XC7A100T-2FGG484I").fits(parse_part_number("XC7A100T-2CSG324I"))


def test_a_different_device_never_stands_in():
    assert not parse_part_number("XC7A200T-2FBG676C").fits(parse_part_number("XC7A100T-2FGG484C"))


def test_junction_range_comes_from_the_temperature_grade():
    assert parse_part_number("XC7A100T-2FGG484I").junction_range == (-40, 100)
    assert parse_part_number("XC7A100T-2FGG484C").junction_range == (0, 85)


@pytest.mark.parametrize(
    "code",
    [
        "",
        "   ",
        "XC7A100T",
        "XC7A100T-2FGG484",
        "XC7A100T-FGG484I",
        "EP4CE22F17C6N",
        "XC7A100T-2FGG484X",
    ],
)
def test_junk_is_refused(code):
    with pytest.raises(ValidationError):
        parse_part_number(code)


def test_a_hand_built_part_still_checks_itself():
    with pytest.raises(ValidationError):
        PartNumber(device="xc7a100t", speed_grade="2", package="fgg484", temperature="z")
    with pytest.raises(ValidationError):
        PartNumber(device="lfe5u45", speed_grade="6", package="bg381", temperature="c")


def test_vendor_is_stated_not_guessed():
    assert parse_part_number("XC7A100T-2FGG484I").vendor == "xilinx"
