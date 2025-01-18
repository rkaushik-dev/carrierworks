import pytest

from fpga.catalog.devices import (
    IDCODE_MASK,
    all_devices,
    device_for,
    device_for_idcode,
    devices_in_family,
    lookup_device,
)
from fpga.catalog.partnumber import parse_part_number
from fpga.errors import NotFound


def test_a_part_number_finds_its_silicon():
    spec = device_for(parse_part_number("XC7A100T-2FGG484I"))
    assert spec.device == "xc7a100t"
    assert spec.family == "artix7"
    assert spec.luts == 63400


def test_a_bare_device_code_works_too():
    assert device_for("XC7K325T").idcode == 0x03651093


def test_an_unstocked_device_is_a_not_found():
    with pytest.raises(NotFound) as caught:
        device_for("xc7v2000t")
    assert caught.value.kind == "device"


def test_lookup_returns_nothing_rather_than_raising():
    assert lookup_device("xc7v2000t") is None
    assert lookup_device(" XC7A35T ") is not None


def test_idcode_lookup_ignores_the_revision_nibble():
    plain = device_for_idcode(0x03631093)
    stepped = device_for_idcode(0x13631093)
    assert plain is stepped
    assert plain.device == "xc7a100t"


def test_an_unknown_idcode_reports_the_value_it_saw():
    with pytest.raises(NotFound) as caught:
        device_for_idcode(0x0BAD0093)
    assert caught.value.key == "0x0bad0093"


def test_matches_idcode_agrees_with_the_lookup():
    spec = device_for("xc7a35t")
    assert spec.matches_idcode(0x4362D093)
    assert not spec.matches_idcode(0x03631093)


def test_config_bytes_round_up():
    spec = device_for("xc7a100t")
    assert spec.config_bits == 30_606_304
    assert spec.config_bytes == 3_825_788


def test_block_ram_is_reported_in_bits_as_well():
    assert device_for("xc7a35t").block_ram_bits == 50 * 36 * 1024


def test_a_design_that_fits_is_accepted():
    spec = device_for("xc7a100t")
    assert spec.holds(luts=40000, flip_flops=51000, block_ram_36k=90, dsp_slices=120)


@pytest.mark.parametrize(
    ("luts", "flip_flops", "brams", "dsps"),
    [
        (70000, 1000, 1, 1),
        (1000, 200000, 1, 1),
        (1000, 1000, 400, 1),
        (1000, 1000, 1, 900),
    ],
)
def test_one_resource_over_is_enough_to_refuse(luts, flip_flops, brams, dsps):
    spec = device_for("xc7a100t")
    assert not spec.holds(luts=luts, flip_flops=flip_flops, block_ram_36k=brams, dsp_slices=dsps)


def test_families_group_the_way_the_part_numbers_say():
    artix = {spec.device for spec in devices_in_family("artix7")}
    assert artix == {"xc7a35t", "xc7a50t", "xc7a100t", "xc7a200t"}
    assert devices_in_family("stratix10") == ()


def test_every_catalog_family_matches_the_part_number_grammar():
    for spec in all_devices():
        part = parse_part_number(spec.device.upper() + "-1FGG484C")
        assert part.family == spec.family


def test_idcodes_are_unique_once_masked():
    masked = [spec.idcode & IDCODE_MASK for spec in all_devices()]
    assert len(set(masked)) == len(masked)


def test_every_device_has_a_plausible_ir_length():
    for spec in all_devices():
        assert spec.ir_length in (6, 10)
        assert spec.config_bits > 1_000_000
