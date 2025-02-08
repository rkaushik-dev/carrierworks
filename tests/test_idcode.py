import pytest

from fpga.catalog.devices import all_devices
from fpga.catalog.idcode import ALL_ONES, IdCode, manufacturer_name
from fpga.errors import ValidationError


def test_an_artix_idcode_comes_apart():
    code = IdCode(0x0362D093)
    assert code.manufacturer == 0x049
    assert code.manufacturer_name == "xilinx"
    assert code.part == 0x362D
    assert code.version == 0
    assert code.has_valid_lsb


def test_the_version_nibble_is_the_stepping():
    stepped = IdCode(0x4362D093)
    assert stepped.version == 4
    assert stepped.part == IdCode(0x0362D093).part
    assert stepped.same_device(IdCode(0x0362D093))


def test_without_version_clears_only_the_top_nibble():
    assert IdCode(0xF362D093).without_version() == IdCode(0x0362D093)


def test_two_different_parts_are_not_the_same_device():
    assert not IdCode(0x0362D093).same_device(IdCode(0x03631093))


@pytest.mark.parametrize(
    "text",
    ["0x0362D093", "0362d093", " 0x0362_d093 ", "0X0362D093"],
)
def test_idcodes_parse_however_they_were_written(text):
    assert IdCode.parse(text) == IdCode(0x0362D093)


def test_parsing_an_int_is_allowed_because_drivers_return_ints():
    assert IdCode.parse(0x0362D093).value == 0x0362D093


@pytest.mark.parametrize("text", ["", "   ", "not-an-idcode", "0xzz"])
def test_junk_idcodes_are_refused(text):
    with pytest.raises(ValidationError):
        IdCode.parse(text)


def test_an_idcode_wider_than_the_register_is_refused():
    with pytest.raises(ValidationError):
        IdCode(ALL_ONES + 1)
    with pytest.raises(ValidationError):
        IdCode(-1)


def test_building_reproduces_the_real_thing():
    built = IdCode.build(manufacturer=0x049, part=0x362D, version=0)
    assert built == IdCode(0x0362D093)


@pytest.mark.parametrize(
    ("manufacturer", "part", "version"),
    [(0x800, 0x1234, 0), (0x049, 0x10000, 0), (0x049, 0x1234, 16), (-1, 0, 0)],
)
def test_building_out_of_range_is_refused(manufacturer, part, version):
    with pytest.raises(ValidationError):
        IdCode.build(manufacturer=manufacturer, part=part, version=version)


def test_an_open_chain_reads_as_all_ones_and_is_not_a_device():
    floating = IdCode(ALL_ONES)
    assert floating.is_floating
    assert not floating.is_plausible


@pytest.mark.parametrize(
    "value",
    [0x00000000, 0x0362D092, 0xFFFFFFFF, 0x00000001, 0x0000FFFF],
)
def test_wiring_faults_are_not_plausible_devices(value):
    assert not IdCode(value).is_plausible


@pytest.mark.parametrize("value", [0x0362D093, 0x4BA00477, 0x01111043])
def test_real_looking_idcodes_are_plausible(value):
    assert IdCode(value).is_plausible


def test_the_arm_debug_port_reports_as_arm():
    assert IdCode(0x4BA00477).manufacturer_name == "arm"


def test_an_unmet_manufacturer_reports_its_number():
    assert manufacturer_name(0x111) == "unknown-0x111"


def test_idcodes_print_as_padded_hex():
    assert str(IdCode(0x362D093)) == "0x0362d093"


def test_every_catalogued_device_carries_a_xilinx_idcode():
    for spec in all_devices():
        code = IdCode(spec.idcode)
        assert code.manufacturer_name == "xilinx"
        assert code.is_plausible
        assert code.version == 0
