import pytest

from fpga.errors import ValidationError
from fpga.measure import (
    Frequency,
    format_size,
    kib,
    mib,
    parse_size,
    picoseconds_from_ns,
    render_ns,
)


def test_helpers_are_binary():
    assert kib(64) == 65536
    assert mib(16) == 16777216


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("512", 512),
        ("512 B", 512),
        ("64 KiB", 65536),
        ("64kb", 65536),
        ("16 MiB", 16777216),
        ("1.5 MiB", 1572864),
        ("2 GiB", 2147483648),
    ],
)
def test_sizes_read_the_way_datasheets_write_them(text, expected):
    assert parse_size(text) == expected


@pytest.mark.parametrize("text", ["", "MiB", "1.5 B", "12 furlongs", "-4 KiB", "0x40"])
def test_bad_sizes_are_refused(text):
    with pytest.raises(ValidationError):
        parse_size(text)


@pytest.mark.parametrize(
    ("size", "expected"),
    [
        (0, "0 B"),
        (900, "900 B"),
        (65536, "64 KiB"),
        (16777216, "16 MiB"),
        (1572864, "1.5 MiB"),
        (3_825_788, "3.6 MiB"),
    ],
)
def test_sizes_format_back_readably(size, expected):
    assert format_size(size) == expected


def test_formatting_a_negative_size_is_a_bug_worth_raising():
    with pytest.raises(ValidationError):
        format_size(-1)


def test_frequency_constructors_agree():
    assert Frequency.megahertz(30) == Frequency(30_000_000)
    assert Frequency.kilohertz(1500) == Frequency(1_500_000)
    assert Frequency.megahertz(1.5) == Frequency(1_500_000)


@pytest.mark.parametrize(
    ("text", "hertz"),
    [
        ("30 MHz", 30_000_000),
        ("30MHz", 30_000_000),
        ("6.25 MHz", 6_250_000),
        ("100 kHz", 100_000),
        ("400", 400),
    ],
)
def test_frequencies_parse(text, hertz):
    assert Frequency.parse(text).hertz == hertz


@pytest.mark.parametrize("text", ["", "fast", "30 rpm", "-5 MHz"])
def test_bad_frequencies_are_refused(text):
    with pytest.raises(ValidationError):
        Frequency.parse(text)


def test_zero_is_not_a_clock():
    with pytest.raises(ValidationError):
        Frequency(0)


def test_period_is_whole_picoseconds():
    assert Frequency.megahertz(100).period_ps == 10_000
    assert Frequency.megahertz(300).period_ps == 3_333


def test_dividing_matches_what_an_adapter_does():
    assert Frequency.megahertz(30).divided_by(4) == Frequency(7_500_000)
    with pytest.raises(ValidationError):
        Frequency.megahertz(30).divided_by(0)


def test_at_most_holds_a_chain_to_its_slowest_device():
    fast = Frequency.megahertz(30)
    slow = Frequency.megahertz(6)
    assert fast.at_most(slow) == slow
    assert slow.at_most(fast) == slow


def test_frequencies_order():
    assert Frequency.megahertz(6) < Frequency.megahertz(30)
    assert max(Frequency.megahertz(6), Frequency.megahertz(30)).hertz == 30_000_000


@pytest.mark.parametrize(
    ("hertz", "text"),
    [
        (30_000_000, "30 MHz"),
        (6_250_000, "6.250 MHz"),
        (100_000, "100 kHz"),
        (1_500, "1.500 kHz"),
        (400, "400 Hz"),
    ],
)
def test_frequencies_print(hertz, text):
    assert str(Frequency(hertz)) == text


@pytest.mark.parametrize(
    ("text", "picoseconds"),
    [
        ("0.147", 147),
        ("-0.023", -23),
        ("12.500", 12500),
        ("3", 3000),
        ("-1.2", -1200),
    ],
)
def test_report_nanoseconds_become_picoseconds(text, picoseconds):
    assert picoseconds_from_ns(text) == picoseconds


def test_floats_are_accepted_but_still_pinned_to_picoseconds():
    assert picoseconds_from_ns(0.147) == 147
    assert picoseconds_from_ns(-2.0) == -2000


@pytest.mark.parametrize("text", ["", "  ", "0.1234", "nope", "1e-9"])
def test_timing_values_with_too_much_precision_are_refused(text):
    with pytest.raises(ValidationError):
        picoseconds_from_ns(text)


@pytest.mark.parametrize("picoseconds", [147, -23, 12500, 0, -1200])
def test_timing_values_round_trip(picoseconds):
    assert picoseconds_from_ns(render_ns(picoseconds)) == picoseconds


def test_rendering_pads_the_fraction():
    assert render_ns(7) == "0.007"
    assert render_ns(-7) == "-0.007"
