import pytest

from fpga.electrical import RailSpec, milliwatts, parse_current, parse_voltage
from fpga.errors import ValidationError


def vccint():
    return RailSpec(name="vccint", nominal_mv=1000, tolerance_percent=5, max_current_ma=4000)


def vadj():
    return RailSpec(
        name="vadj",
        nominal_mv=1800,
        tolerance_percent=3,
        max_current_ma=2000,
        sequence=3,
        adjustable=True,
    )


@pytest.mark.parametrize(
    ("text", "millivolts"),
    [
        ("3v3", 3300),
        ("1v8", 1800),
        ("3.3V", 3300),
        ("3.30 V", 3300),
        ("1.0", 1000),
        ("5v", 5000),
        ("0.85V", 850),
    ],
)
def test_voltages_read_however_the_lab_wrote_them(text, millivolts):
    assert parse_voltage(text) == millivolts


def test_an_integer_voltage_is_already_millivolts():
    assert parse_voltage(1800) == 1800


@pytest.mark.parametrize("text", ["", "high", "1.2345", "v", "-1.8"])
def test_junk_voltages_are_refused(text):
    with pytest.raises(ValidationError):
        parse_voltage(text)


def test_a_negative_integer_voltage_is_refused():
    with pytest.raises(ValidationError):
        parse_voltage(-1)


@pytest.mark.parametrize(
    ("text", "milliamps"),
    [
        ("500", 500),
        ("500ma", 500),
        ("1.5A", 1500),
        ("2 a", 2000),
        ("0.25A", 250),
    ],
)
def test_currents_read_in_milliamps(text, milliamps):
    assert parse_current(text) == milliamps


def test_a_fraction_of_a_milliamp_is_refused():
    with pytest.raises(ValidationError):
        parse_current("0.5ma")


@pytest.mark.parametrize("text", ["", "lots", "-4"])
def test_junk_currents_are_refused(text):
    with pytest.raises(ValidationError):
        parse_current(text)


def test_power_is_whole_milliwatts():
    assert milliwatts(1000, 4000) == 4000
    assert milliwatts(1800, 250) == 450
    assert milliwatts(1799, 1) == 2


def test_power_of_a_negative_reading_is_a_bug():
    with pytest.raises(ValidationError):
        milliwatts(-1000, 10)


def test_the_window_comes_from_the_tolerance():
    rail = vccint()
    assert rail.low_limit_mv == 950
    assert rail.high_limit_mv == 1050
    assert rail.contains(950)
    assert rail.contains(1050)
    assert not rail.contains(949)
    assert not rail.contains(1051)


def test_the_low_limit_rounds_inward():
    rail = RailSpec(name="vccaux", nominal_mv=1799, tolerance_percent=3, max_current_ma=500)
    assert rail.low_limit_mv == 1746
    assert rail.high_limit_mv == 1852


def test_deviation_is_signed_parts_per_thousand():
    rail = vccint()
    assert rail.deviation_permille(1000) == 0
    assert rail.deviation_permille(970) == -30
    assert rail.deviation_permille(1020) == 20


def test_rail_names_are_normalised():
    rail = RailSpec(name="VCC INT", nominal_mv=1000, tolerance_percent=5, max_current_ma=1)
    assert rail.name == "vcc-int"


@pytest.mark.parametrize(
    ("nominal", "tolerance", "current"),
    [(0, 5, 100), (1000, 0, 100), (1000, 30, 100), (1000, 5, 0)],
)
def test_a_nonsense_rail_is_refused(nominal, tolerance, current):
    with pytest.raises(ValidationError):
        RailSpec(
            name="bad",
            nominal_mv=nominal,
            tolerance_percent=tolerance,
            max_current_ma=current,
        )


def test_a_healthy_reading_has_no_fault():
    reading = vccint().reading(998, 3200)
    assert reading.in_window
    assert reading.fault() is None
    assert reading.power_mw == 3194


def test_a_sagging_rail_reports_under_voltage():
    reading = vccint().reading(910, 3800)
    assert reading.sagging
    assert reading.fault() == "under_voltage"


def test_an_overshooting_rail_reports_over_voltage():
    reading = vccint().reading(1100, 100)
    assert not reading.sagging
    assert reading.fault() == "over_voltage"


def test_over_current_wins_over_a_voltage_complaint():
    reading = vccint().reading(910, 4500)
    assert reading.over_current
    assert reading.fault() == "over_current"


def test_max_power_uses_the_top_of_the_window():
    assert vadj().max_power_mw == 3708


def test_readings_print_the_way_people_read_them_out():
    assert str(vccint().reading(998, 3200)) == "vccint 0.998V 3200mA"
    assert str(vadj().reading(1800, 0)) == "vadj 1.8V 0mA"
