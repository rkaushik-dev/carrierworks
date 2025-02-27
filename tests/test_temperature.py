import pytest

from fpga.catalog.partnumber import parse_part_number
from fpga.errors import ValidationError
from fpga.temperature import (
    ThermalLimits,
    from_xadc_code,
    parse_temperature,
    render_temperature,
    to_xadc_code,
)


def limits():
    return ThermalLimits(warn_mc=76_000, critical_mc=86_000, shutdown_mc=90_000)


@pytest.mark.parametrize(
    ("text", "millidegrees"),
    [
        ("62", 62_000),
        ("62.5", 62_500),
        ("62.5C", 62_500),
        ("62.5 degC", 62_500),
        ("-40", -40_000),
        ("-0.5", -500),
    ],
)
def test_temperatures_read_however_they_were_written(text, millidegrees):
    assert parse_temperature(text) == millidegrees


def test_numbers_come_through_as_themselves():
    assert parse_temperature(62) == 62_000
    assert parse_temperature(62.5) == 62_500


@pytest.mark.parametrize("text", ["", "warm", "62.5555", "sixty"])
def test_junk_temperatures_are_refused(text):
    with pytest.raises(ValidationError):
        parse_temperature(text)


def test_rendering_gives_one_decimal():
    assert render_temperature(62_500) == "62.5C"
    assert render_temperature(-40_000) == "-40.0C"


def test_the_system_monitor_formula_matches_the_user_guide():
    assert from_xadc_code(0x0000) == -273_150
    assert from_xadc_code(0x0800) == 251_987 - 273_150
    assert from_xadc_code(0x0FFF) == 230_701


def test_a_realistic_code_lands_at_a_realistic_temperature():
    reading = from_xadc_code(0x09C4)
    assert 34_000 < reading < 36_000


def test_codes_round_trip_within_a_count():
    for code in (0x0800, 0x0900, 0x0A50, 0x0B00):
        assert abs(to_xadc_code(from_xadc_code(code)) - code) <= 1


@pytest.mark.parametrize("code", [-1, 0x1000])
def test_codes_outside_twelve_bits_are_refused(code):
    with pytest.raises(ValidationError):
        from_xadc_code(code)


def test_a_temperature_the_sensor_cannot_report_is_refused():
    with pytest.raises(ValidationError):
        to_xadc_code(500_000)
    with pytest.raises(ValidationError):
        to_xadc_code(-300_000)


def test_limits_have_to_climb():
    with pytest.raises(ValidationError):
        ThermalLimits(warn_mc=90_000, critical_mc=86_000, shutdown_mc=95_000)
    with pytest.raises(ValidationError):
        ThermalLimits(warn_mc=10_000, critical_mc=20_000, shutdown_mc=30_000, hysteresis_mc=0)


def test_limits_come_off_the_part_temperature_grade():
    part = parse_part_number("XC7A100T-2FGG484I")
    industrial = ThermalLimits.from_junction_range(part.junction_range)
    assert industrial.shutdown_mc == 100_000
    assert industrial.critical_mc == 96_000
    assert industrial.warn_mc == 86_000


def test_a_commercial_part_gets_colder_limits_than_an_industrial_one():
    commercial = ThermalLimits.from_junction_range((0, 85))
    industrial = ThermalLimits.from_junction_range((-40, 100))
    assert commercial.shutdown_mc < industrial.shutdown_mc
    assert commercial.warn_mc < industrial.warn_mc


def test_an_override_survives_the_derivation():
    derived = ThermalLimits.from_junction_range((0, 85), warn_mc=60_000)
    assert derived.warn_mc == 60_000
    assert derived.shutdown_mc == 85_000


@pytest.mark.parametrize(
    ("millidegrees", "state"),
    [
        (40_000, "normal"),
        (75_999, "normal"),
        (76_000, "warn"),
        (85_999, "warn"),
        (86_000, "critical"),
        (89_999, "critical"),
        (90_000, "shutdown"),
        (120_000, "shutdown"),
    ],
)
def test_a_cold_start_lands_in_the_obvious_band(millidegrees, state):
    assert limits().state_for(millidegrees) == state


def test_climbing_happens_the_moment_the_threshold_is_crossed():
    assert limits().next_state("normal", 76_000) == "warn"
    assert limits().next_state("warn", 86_000) == "critical"
    assert limits().next_state("critical", 90_000) == "shutdown"


def test_a_jump_skips_straight_to_the_hottest_band_it_earns():
    assert limits().next_state("normal", 91_000) == "shutdown"


def test_falling_back_needs_a_whole_hysteresis_band():
    live = limits()
    assert live.next_state("critical", 85_000) == "critical"
    assert live.next_state("critical", 83_001) == "critical"
    assert live.next_state("critical", 83_000) == "warn"


def test_it_can_fall_through_two_bands_at_once_when_it_cools_properly():
    assert limits().next_state("critical", 60_000) == "normal"


def test_sitting_on_a_threshold_does_not_chatter():
    live = limits()
    state = "normal"
    for reading in (76_000, 75_900, 76_100, 75_800, 76_000):
        state = live.next_state(state, reading)
        assert state == "warn"


def test_an_unknown_state_going_in_is_refused():
    with pytest.raises(ValidationError):
        limits().next_state("toasty", 40_000)


def test_asking_for_the_threshold_of_normal_is_a_bug():
    with pytest.raises(ValidationError):
        limits().threshold_for("normal")
