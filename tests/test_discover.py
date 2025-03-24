import pytest

from fpga.errors import ChainError
from fpga.jtag.bits import BitVector
from fpga.jtag.chain import ScanChain, TapPosition
from fpga.jtag.discover import chain_from_scan, discover_taps, scan_bits_for

ARTIX = 0x03631093
KINTEX = 0x03651093
SPARTAN = 0x037C4093


def capture(*words, bypass_after=()):
    vector = BitVector(0, 0)
    for index, word in enumerate(words):
        if index in bypass_after:
            vector = vector + BitVector.zeros(1)
        vector = vector + BitVector(word, 32)
    return vector + BitVector.ones(32)


def test_one_device_reads_back_as_one_device():
    found = discover_taps(capture(ARTIX))
    assert len(found) == 1
    assert found[0].device == "xc7a100t"
    assert found[0].recognised
    assert not found[0].in_bypass


def test_the_first_idcode_out_is_the_device_nearest_tdo():
    found = discover_taps(capture(KINTEX, ARTIX))
    assert [tap.device for tap in found] == ["xc7k325t", "xc7a100t"]


def test_a_bypass_bit_is_a_device_with_nothing_to_say():
    found = discover_taps(capture(ARTIX, bypass_after=(0,)))
    assert len(found) == 2
    assert found[0].in_bypass
    assert found[0].describe() == "bypass"
    assert found[1].device == "xc7a100t"


def test_an_unknown_idcode_is_reported_rather_than_dropped():
    found = discover_taps(capture(0x01234093))
    assert len(found) == 1
    assert found[0].idcode is not None
    assert found[0].device is None
    assert not found[0].recognised
    assert found[0].describe() == "unknown 0x01234093"


def test_an_empty_chain_reads_as_all_ones_and_is_refused():
    with pytest.raises(ChainError) as caught:
        discover_taps(BitVector.ones(32))
    assert "nothing answered" in str(caught.value)


def test_a_chain_that_stops_mid_idcode_is_refused():
    truncated = BitVector(ARTIX, 32).slice(0, 20)
    with pytest.raises(ChainError):
        discover_taps(truncated)


def test_something_that_is_not_an_idcode_at_all_is_refused():
    with pytest.raises(ChainError):
        discover_taps(BitVector(0x0000FFFF, 32) + BitVector.ones(32))


def test_a_chain_longer_than_the_bench_believes_in_is_refused():
    vector = BitVector(0, 0)
    for _ in range(40):
        vector = vector + BitVector.zeros(1)
    with pytest.raises(ChainError):
        discover_taps(vector + BitVector.ones(32))


def test_a_scan_of_a_known_chain_round_trips():
    chain = ScanChain.of_devices("xc7a100t", "xc7k325t")
    rebuilt = chain_from_scan(scan_bits_for(chain))
    assert rebuilt.labels() == chain.labels()
    assert [tap.device for tap in rebuilt.taps] == ["xc7a100t", "xc7k325t"]
    assert rebuilt.ir_length == chain.ir_length


def test_the_rebuilt_chain_is_in_tdi_order():
    chain = ScanChain.of_devices("xc7s25", "xc7a100t", "xc7k325t")
    rebuilt = chain_from_scan(scan_bits_for(chain))
    assert [tap.device for tap in rebuilt.taps] == ["xc7s25", "xc7a100t", "xc7k325t"]


def test_the_stepping_nibble_does_not_stop_a_device_being_recognised():
    stepped = capture(ARTIX | 0x40000000)
    found = discover_taps(stepped)
    assert found[0].device == "xc7a100t"


def test_a_rebuilt_position_stores_the_idcode_without_its_stepping():
    rebuilt = chain_from_scan(capture(ARTIX | 0x40000000))
    assert rebuilt.taps[0].expected_idcode == ARTIX


def test_a_chain_with_an_unknown_part_cannot_be_rebuilt():
    with pytest.raises(ChainError) as caught:
        chain_from_scan(capture(0x01234093))
    assert "catalog" in str(caught.value)


def test_a_chain_with_a_bypassed_device_cannot_be_rebuilt_either():
    with pytest.raises(ChainError):
        chain_from_scan(capture(ARTIX, bypass_after=(0,)))


def test_scan_bits_pad_a_bypassed_position_with_a_single_zero():
    chain = ScanChain(
        (
            TapPosition.for_device("u1", "xc7a100t"),
            TapPosition(label="u2", ir_length=4),
        )
    )
    bits = scan_bits_for(chain)
    assert len(bits) == 1 + 32 + 32
    assert bits.bit(0) == 0


def test_scan_bits_end_in_the_ones_that_were_shifted_in():
    bits = scan_bits_for(ScanChain.of_devices("xc7s25"))
    assert bits.slice(32, 32) == BitVector.ones(32)
    assert discover_taps(bits)[0].device == "xc7s25"
    assert BitVector(SPARTAN, 32) == bits.slice(0, 32)
