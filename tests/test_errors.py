import pytest

from fpga.errors import (
    BenchError,
    CatalogError,
    ConfigurationError,
    DeviceMismatch,
    NotFound,
    TransientBenchError,
    ValidationError,
)


def test_message_survives_str():
    assert str(BenchError("chain did not settle")) == "chain did not settle"


def test_details_render_sorted_and_repr_quoted():
    err = BenchError("bad shift", tap="u2", bits=17)
    assert str(err) == "bad shift (bits=17, tap='u2')"


def test_as_dict_is_the_wire_shape():
    err = ValidationError("speed grade unknown", field="part", given="-9")
    assert err.as_dict() == {
        "code": "validation",
        "message": "speed grade unknown",
        "retryable": False,
        "details": {"field": "part", "given": "-9"},
    }


def test_details_are_copied_not_shared():
    err = BenchError("x", a=1)
    err.as_dict()["details"]["a"] = 2
    assert err.details["a"] == 1


def test_only_transient_errors_are_retryable():
    assert TransientBenchError("adapter went away").retryable is True
    assert ConfigurationError("two boards claim shelf 4").retryable is False
    assert NotFound("board", "bx-14").retryable is False


def test_not_found_carries_kind_and_key():
    err = NotFound("bitstream", "9f2c11")
    assert err.kind == "bitstream"
    assert err.key == "9f2c11"
    assert str(err) == "no bitstream known as 9f2c11 (key='9f2c11', kind='bitstream')"


def test_catalog_error_is_a_configuration_error():
    assert isinstance(CatalogError("idcode listed twice"), ConfigurationError)


def test_device_mismatch_keeps_both_sides():
    err = DeviceMismatch(
        "image is not for this part",
        expected="xc7a100t",
        found="xc7k325t",
        board="bx-14",
    )
    assert (err.expected, err.found) == ("xc7a100t", "xc7k325t")
    assert err.as_dict()["details"]["board"] == "bx-14"
    assert err.code == "device_mismatch"


def test_validation_error_without_a_field_stays_clean():
    assert ValidationError("empty chain").details == {}


@pytest.mark.parametrize(
    "error",
    [
        ConfigurationError("x"),
        ValidationError("x"),
        NotFound("board", "y"),
        DeviceMismatch("x", expected="a", found="b"),
    ],
)
def test_everything_is_a_bench_error(error):
    assert isinstance(error, BenchError)
