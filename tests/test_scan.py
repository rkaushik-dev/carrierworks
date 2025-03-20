import pytest

from fpga.errors import NotFound, ValidationError
from fpga.jtag.bits import BitVector
from fpga.jtag.chain import ScanChain, TapPosition
from fpga.jtag.scan import (
    bypass_instruction,
    dr_capture_offset,
    dr_scan,
    instruction_code,
    ir_scan,
    take_dr_payload,
)


def chain():
    return ScanChain.of_devices("xc7a100t", "xc7k325t")


def mixed():
    return ScanChain(
        (
            TapPosition.for_device("u1", "xc7a100t"),
            TapPosition(label="u2", ir_length=4),
        )
    )


def test_bypass_is_all_ones_at_any_width():
    assert str(bypass_instruction(6)) == "111111"
    assert str(bypass_instruction(4)) == "1111"


def test_named_instructions_come_out_at_the_device_width():
    assert str(instruction_code("idcode", 6)) == "001001"
    assert str(instruction_code("cfg_in", 6)) == "000101"
    assert str(instruction_code("jprogram", 6)) == "001011"


def test_bypass_widens_to_whatever_the_device_needs():
    assert str(instruction_code("bypass", 8)) == "11111111"


def test_an_instruction_that_does_not_fit_is_refused():
    with pytest.raises(ValidationError):
        instruction_code("user3", 4)


def test_an_instruction_nobody_has_heard_of_is_a_not_found():
    with pytest.raises(NotFound):
        instruction_code("selftest", 6)


def test_an_all_bypass_scan_is_all_ones():
    assert str(ir_scan(chain())) == "111111111111"


def test_the_named_device_gets_its_instruction_and_the_rest_bypass():
    scan = ir_scan(chain(), {"u1": "idcode"})
    # u2 sits nearest tdo, so its bypass bits shift out first and print last.
    assert str(scan) == "001001111111"


def test_naming_the_device_nearest_tdo_puts_its_bits_at_the_shifting_end():
    scan = ir_scan(chain(), {"u2": "idcode"})
    assert str(scan) == "111111001001"
    assert scan.slice(0, 6) == BitVector(0b001001, 6)


def test_both_devices_can_be_named_at_once():
    scan = ir_scan(chain(), {"u1": "cfg_in", "u2": "jstart"})
    assert str(scan) == "000101001100"


def test_widths_follow_each_device_on_a_mixed_chain():
    scan = ir_scan(mixed(), {"u1": "idcode"})
    assert len(scan) == 10
    assert str(scan) == "0010011111"


def test_naming_a_device_that_is_not_on_the_chain_is_a_not_found():
    with pytest.raises(NotFound):
        ir_scan(chain(), {"u9": "idcode"})


def test_a_data_scan_pads_one_bit_for_every_other_device():
    payload = BitVector.parse("1011")
    scan = dr_scan(chain(), "u1", payload)
    assert len(scan) == 5
    assert str(scan) == "10110"


def test_padding_lands_on_the_correct_side():
    payload = BitVector.parse("1011")
    scan = dr_scan(chain(), "u2", payload)
    assert str(scan) == "01011"
    assert scan.bit(0) == 1
    assert scan.bit(4) == 0


def test_the_capture_offset_counts_devices_nearer_tdo():
    assert dr_capture_offset(chain(), "u1") == 1
    assert dr_capture_offset(chain(), "u2") == 0


def test_a_payload_is_pulled_back_out_of_a_whole_capture():
    idcode = BitVector(0x03631093, 32)
    captured = BitVector.zeros(1) + idcode
    payload = take_dr_payload(chain(), "u1", captured, 32)
    assert len(payload) == 32
    assert payload == idcode


def test_a_capture_of_the_wrong_length_is_refused():
    with pytest.raises(ValidationError):
        take_dr_payload(chain(), "u1", BitVector.zeros(32), 32)


def test_a_scan_and_its_extraction_agree_on_a_three_device_chain():
    three = ScanChain.of_devices("xc7a35t", "xc7a100t", "xc7s25")
    payload = BitVector.parse("110000111100001111000011110000")
    scan = dr_scan(three, "u2", payload)
    assert len(scan) == len(payload) + 2
    assert take_dr_payload(three, "u2", scan, len(payload)) == payload
