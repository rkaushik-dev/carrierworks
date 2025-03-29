import pytest

from fpga.errors import ChainError
from fpga.jtag.bits import BitVector
from fpga.jtag.chain import ScanChain
from fpga.jtag.discover import scan_bits_for
from fpga.jtag.verify import compare_chains, diagnose_capture, verify_chain


def expected_chain():
    return ScanChain.of_devices("xc7a100t", "xc7k325t")


def test_a_healthy_capture_diagnoses_nothing():
    assert diagnose_capture(scan_bits_for(expected_chain())) is None


def test_an_open_chain_is_named_as_one():
    reading = diagnose_capture(BitVector.ones(64))
    assert reading is not None
    assert "open chain" in reading


def test_a_chain_held_low_is_named_as_that():
    reading = diagnose_capture(BitVector.zeros(64))
    assert reading is not None
    assert "no power" in reading


def test_an_empty_capture_says_so():
    assert diagnose_capture(BitVector(0, 0)) == "nothing came back at all"


def test_a_first_device_that_did_not_answer_is_called_out():
    capture = BitVector.ones(32) + BitVector(0x03631093, 32) + BitVector.ones(32)
    reading = diagnose_capture(capture)
    assert reading is not None
    assert "first device" in reading


def test_a_matching_chain_verifies_and_comes_back():
    chain = expected_chain()
    found = verify_chain(chain, scan_bits_for(chain))
    assert found.labels() == chain.labels()


def test_two_identical_chains_differ_in_no_way():
    assert compare_chains(expected_chain(), expected_chain()) == ()


def test_a_missing_device_shows_up_as_a_length_difference():
    shorter = ScanChain.of_devices("xc7a100t")
    differences = compare_chains(expected_chain(), shorter)
    kinds = [difference.kind for difference in differences]
    assert "length" in kinds
    assert differences[0].describe() == "the chain has 1 devices, expected 2"


def test_a_wrong_part_at_a_position_is_reported_with_the_position():
    wrong = ScanChain.of_devices("xc7a100t", "xc7a35t")
    differences = compare_chains(expected_chain(), wrong)
    assert len(differences) == 1
    assert differences[0].kind == "device"
    assert differences[0].position == 1
    assert differences[0].describe() == "position 2 holds xc7a35t, expected xc7k325t"


def test_a_swapped_chain_is_called_out_as_order_rather_than_as_two_wrong_parts():
    swapped = ScanChain.of_devices("xc7k325t", "xc7a100t")
    differences = compare_chains(expected_chain(), swapped)
    assert [difference.kind for difference in differences] == ["order", "order"]
    assert "belongs elsewhere" in differences[0].describe()


def test_verification_refuses_a_chain_that_does_not_match():
    wrong = ScanChain.of_devices("xc7a100t", "xc7a35t")
    with pytest.raises(ChainError) as caught:
        verify_chain(expected_chain(), scan_bits_for(wrong))
    assert caught.value.details["found"] == wrong.describe()
    assert caught.value.details["differences"] == [
        "position 2 holds xc7a35t, expected xc7k325t",
    ]


def test_verification_reports_wiring_before_it_reports_devices():
    with pytest.raises(ChainError) as caught:
        verify_chain(expected_chain(), BitVector.ones(96))
    assert "open chain" in caught.value.message
    assert caught.value.details["expected"] == expected_chain().describe()


def test_a_chain_error_is_retryable_because_it_is_usually_the_cable():
    with pytest.raises(ChainError) as caught:
        verify_chain(expected_chain(), BitVector.zeros(96))
    assert caught.value.retryable is True


def test_an_extra_device_on_the_end_is_a_length_and_nothing_else():
    longer = ScanChain.of_devices("xc7a100t", "xc7k325t", "xc7s25")
    differences = compare_chains(expected_chain(), longer)
    assert [difference.kind for difference in differences] == ["length"]


def test_a_three_device_chain_verifies_end_to_end():
    chain = ScanChain.of_devices("xc7s25", "xc7a35t", "xc7k70t")
    assert verify_chain(chain, scan_bits_for(chain)).describe() == chain.describe()
