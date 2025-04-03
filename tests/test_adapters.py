import pytest

from fpga.errors import CatalogError, NotFound, ValidationError
from fpga.jtag.adapters import (
    AdapterSpec,
    AdapterUnit,
    adapter_model,
    all_adapter_models,
    negotiate_clock,
)
from fpga.measure import Frequency


def hs2():
    return adapter_model("jtag-hs2")


def test_a_model_is_found_by_name():
    assert hs2().vendor == "digilent"
    assert hs2().max_clock == Frequency.megahertz(30)


def test_an_unknown_model_is_a_not_found():
    with pytest.raises(NotFound):
        adapter_model("wiggler")


def test_voltage_support_is_a_membership_question():
    assert hs2().drives(1800)
    assert hs2().drives(5000)
    assert not hs2().drives(1200)
    assert adapter_model("ft2232h-mini").lowest_voltage_mv() == 3300


def test_a_unit_normalises_its_serial_and_checks_its_model():
    unit = AdapterUnit(serial="210308ab1234", model="JTAG HS2", port="usb-3.1")
    assert unit.serial == "210308AB1234"
    assert unit.model == "jtag-hs2"
    assert unit.spec.vendor == "digilent"
    assert unit.describe() == "jtag-hs2 210308AB1234 on usb-3.1"


def test_a_unit_of_a_model_nobody_stocks_is_refused():
    with pytest.raises(NotFound):
        AdapterUnit(serial="210308AB1234", model="wiggler")


def test_a_unit_with_a_junk_serial_is_refused():
    with pytest.raises(ValidationError):
        AdapterUnit(serial="nope!", model="jtag-hs2")


def test_a_unit_without_a_port_describes_itself_anyway():
    assert AdapterUnit(serial="210308AB1234", model="jtag-hs2").describe() == (
        "jtag-hs2 210308AB1234"
    )


def test_a_single_device_chain_runs_at_the_lowest_ceiling():
    clock = negotiate_clock(hs2(), devices=1, site_ceiling_hz=30_000_000)
    assert clock == Frequency.megahertz(30)
    held = negotiate_clock(hs2(), devices=1, site_ceiling_hz=10_000_000)
    assert held == Frequency.megahertz(10)


def test_the_board_gets_a_say_as_well():
    clock = negotiate_clock(
        hs2(),
        devices=1,
        site_ceiling_hz=30_000_000,
        board_ceiling_hz=6_000_000,
    )
    assert clock == Frequency.megahertz(6)


def test_the_adapter_ceiling_applies_even_when_the_site_allows_more():
    clock = negotiate_clock(
        adapter_model("platform-cable-ii"),
        devices=1,
        site_ceiling_hz=30_000_000,
    )
    assert clock == Frequency.megahertz(24)


@pytest.mark.parametrize(
    ("devices", "hertz"),
    [
        (1, 30_000_000),
        (2, 24_000_000),
        (3, 18_000_000),
        (4, 12_000_000),
        (5, 6_000_000),
        (6, 6_000_000),
        (9, 6_000_000),
    ],
)
def test_every_extra_device_costs_a_fifth_of_the_clock(devices, hertz):
    clock = negotiate_clock(hs2(), devices=devices, site_ceiling_hz=30_000_000)
    assert clock.hertz == hertz


def test_derating_never_falls_through_the_floor():
    clock = negotiate_clock(hs2(), devices=12, site_ceiling_hz=30_000_000)
    assert clock.hertz >= 1_000_000


def test_a_ceiling_already_below_the_floor_is_not_raised_to_it():
    clock = negotiate_clock(hs2(), devices=8, site_ceiling_hz=500_000)
    assert clock.hertz == 500_000


@pytest.mark.parametrize(
    ("devices", "site", "board"),
    [(0, 30_000_000, None), (1, 0, None), (1, 30_000_000, 0)],
)
def test_nonsense_negotiation_inputs_are_refused(devices, site, board):
    with pytest.raises(ValidationError):
        negotiate_clock(hs2(), devices=devices, site_ceiling_hz=site, board_ceiling_hz=board)


@pytest.mark.parametrize(
    ("clock", "voltages", "channels"),
    [(0, (3300,), 1), (30_000_000, (), 1), (30_000_000, (3300,), 0)],
)
def test_a_nonsense_adapter_is_refused(clock, voltages, channels):
    with pytest.raises(CatalogError):
        AdapterSpec(
            model="bad",
            vendor="nobody",
            max_clock_hz=clock,
            voltages_mv=voltages,
            channels=channels,
        )


def test_the_catalog_holds_together():
    models = [spec.model for spec in all_adapter_models()]
    assert len(set(models)) == len(models)
    for spec in all_adapter_models():
        assert spec.max_clock_hz >= 1_000_000
        assert all(1000 <= level <= 5000 for level in spec.voltages_mv)


def test_one_model_in_the_catalog_cannot_do_flash():
    assert not adapter_model("arm-usb-tiny-h").can_program_flash
    assert hs2().can_program_flash
