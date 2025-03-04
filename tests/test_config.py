from pathlib import Path

import pytest

from fpga.config import BenchSettings
from fpga.errors import ConfigurationError


def test_the_defaults_are_a_working_bench():
    settings = BenchSettings()
    assert settings.site == "lab-a"
    assert settings.default_lease_minutes == 60
    assert settings.verify_after_program is True
    assert settings.lease_ceiling_seconds == 480 * 60 + 120


def test_the_environment_is_read_with_the_prefix():
    settings = BenchSettings.from_env(
        {
            "FPGA_SITE": "Lab B",
            "FPGA_DEFAULT_LEASE_MINUTES": "30",
            "FPGA_VERIFY_AFTER_PROGRAM": "no",
            "PATH": "/usr/bin",
            "SITE": "somewhere else",
        }
    )
    assert settings.site == "lab-b"
    assert settings.default_lease_minutes == 30
    assert settings.verify_after_program is False


def test_anything_unset_keeps_its_default():
    settings = BenchSettings.from_env({"FPGA_SITE": "lab-c"})
    assert settings.max_lease_minutes == BenchSettings().max_lease_minutes
    assert settings.database_url == BenchSettings().database_url


def test_an_empty_value_is_treated_as_unset():
    settings = BenchSettings.from_env({"FPGA_DEFAULT_LEASE_MINUTES": "  "})
    assert settings.default_lease_minutes == 60


def test_paths_come_through_as_paths():
    settings = BenchSettings.from_env({"FPGA_ARTIFACT_ROOT": "/srv/bitstreams"})
    assert settings.artifact_root == Path("/srv/bitstreams")


@pytest.mark.parametrize("text", ["yes", "true", "1", "ON"])
def test_the_ways_people_write_yes(text):
    assert BenchSettings.from_env({"FPGA_VERIFY_AFTER_PROGRAM": text}).verify_after_program


@pytest.mark.parametrize("text", ["no", "false", "0", "OFF"])
def test_the_ways_people_write_no(text):
    assert not BenchSettings.from_env({"FPGA_VERIFY_AFTER_PROGRAM": text}).verify_after_program


def test_a_value_that_is_neither_is_refused():
    with pytest.raises(ConfigurationError) as caught:
        BenchSettings.from_env({"FPGA_VERIFY_AFTER_PROGRAM": "maybe"})
    assert caught.value.details["setting"] == "verify_after_program"


def test_a_number_that_is_not_one_is_refused():
    with pytest.raises(ConfigurationError):
        BenchSettings.from_env({"FPGA_PROGRAM_ATTEMPTS": "three"})


def test_a_lease_that_expires_immediately_is_refused():
    with pytest.raises(ConfigurationError):
        BenchSettings(default_lease_minutes=0)


def test_a_ceiling_below_the_default_is_refused():
    with pytest.raises(ConfigurationError) as caught:
        BenchSettings(default_lease_minutes=120, max_lease_minutes=60)
    assert caught.value.details["ceiling"] == 60


def test_a_sample_that_goes_stale_before_the_next_poll_is_refused():
    with pytest.raises(ConfigurationError):
        BenchSettings(thermal_poll_seconds=60, thermal_stale_seconds=30)
    with pytest.raises(ConfigurationError):
        BenchSettings(thermal_poll_seconds=0)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("program_attempts", 0),
        ("program_attempts", 11),
        ("program_backoff_seconds", -1),
        ("lease_grace_seconds", -1),
        ("adapter_clock_ceiling_hz", 1000),
        ("adapter_clock_ceiling_hz", 90_000_000),
        ("utc_offset_minutes", -900),
        ("api_key_header", "  "),
    ],
)
def test_settings_outside_their_range_are_refused(field, value):
    with pytest.raises(ConfigurationError):
        BenchSettings(**{field: value})


def test_overriding_keeps_the_checks():
    settings = BenchSettings()
    tighter = settings.override(default_lease_minutes=15)
    assert tighter.default_lease_minutes == 15
    assert settings.default_lease_minutes == 60
    with pytest.raises(ConfigurationError):
        settings.override(max_lease_minutes=1)


def test_overriding_something_that_does_not_exist_says_so():
    with pytest.raises(ConfigurationError) as caught:
        BenchSettings().override(lease_minutes=15)
    assert caught.value.details["setting"] == "lease_minutes"


def test_settings_are_frozen():
    settings = BenchSettings()
    with pytest.raises(AttributeError):
        settings.site = "lab-z"
