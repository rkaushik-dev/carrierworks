import pytest

from fpga.errors import ConfigurationError, NotFound, ValidationError
from fpga.jtag.chain import ScanChain, TapPosition


def two_part_chain():
    return ScanChain.of_devices("xc7a100t", "xc7k325t")


def mixed_chain():
    return ScanChain(
        (
            TapPosition.for_device("u1", "xc7a100t"),
            TapPosition(label="u2", ir_length=4),
            TapPosition.for_device("u3", "xc7s25"),
        )
    )


def test_a_position_from_the_catalog_carries_width_and_idcode():
    tap = TapPosition.for_device("u1", "XC7A100T")
    assert tap.ir_length == 6
    assert tap.expected_idcode == 0x03631093
    assert tap.device == "xc7a100t"
    assert tap.known


def test_a_position_for_an_unknown_part_still_needs_a_width():
    tap = TapPosition(label="U2", ir_length=4)
    assert tap.label == "u2"
    assert not tap.known
    assert tap.expected_idcode is None


def test_a_position_for_a_device_nobody_stocks_is_a_not_found():
    with pytest.raises(NotFound):
        TapPosition.for_device("u1", "xc7v2000t")


@pytest.mark.parametrize("width", [0, 1, 33, -6])
def test_an_implausible_instruction_register_is_refused(width):
    with pytest.raises(ValidationError):
        TapPosition(label="u1", ir_length=width)


def test_an_idcode_that_does_not_fit_is_refused():
    with pytest.raises(ValidationError):
        TapPosition(label="u1", ir_length=6, expected_idcode=0x1_0000_0000)


def test_a_chain_needs_at_least_one_device():
    with pytest.raises(ConfigurationError):
        ScanChain(())


def test_two_positions_cannot_share_a_label():
    with pytest.raises(ConfigurationError):
        ScanChain((TapPosition("u1", 6), TapPosition("u1", 6)))


def test_the_chain_totals_its_instruction_registers():
    assert two_part_chain().ir_length == 12
    assert mixed_chain().ir_length == 16


def test_positions_are_found_by_label():
    chain = two_part_chain()
    assert chain.index_of("u2") == 1
    assert chain.tap("u1").device == "xc7a100t"
    with pytest.raises(NotFound):
        chain.index_of("u9")


def test_indexing_off_the_end_is_refused():
    with pytest.raises(ValidationError):
        two_part_chain().at(2)
    with pytest.raises(ValidationError):
        two_part_chain().at(-1)


def test_the_last_listed_device_is_the_one_nearest_tdo():
    chain = mixed_chain()
    assert chain.devices_before_tdo(2) == 0
    assert chain.devices_before_tdo(0) == 2
    assert chain.devices_after_tdo(0) == 0
    assert chain.devices_after_tdo(2) == 2


def test_instruction_bits_split_around_a_position():
    chain = mixed_chain()
    index = chain.index_of("u2")
    assert chain.ir_bits_before(index) == 6
    assert chain.ir_bits_after(index) == 6
    assert chain.ir_bits_before(index) + chain.ir_bits_after(index) + 4 == chain.ir_length


def test_the_first_position_has_nothing_on_its_tdi_side():
    chain = two_part_chain()
    assert chain.ir_bits_after(0) == 0
    assert chain.ir_bits_before(1) == 0


def test_tdo_order_is_the_reverse_of_the_listed_order():
    chain = mixed_chain()
    assert [tap.label for tap in chain.in_tdo_order()] == ["u3", "u2", "u1"]
    assert chain.labels() == ("u1", "u2", "u3")


def test_a_chain_describes_itself_for_the_log():
    assert two_part_chain().describe() == "u1:xc7a100t/ir6 -> u2:xc7k325t/ir6"
    assert "unknown" in mixed_chain().describe()


def test_building_from_devices_labels_them_in_order():
    chain = ScanChain.of_devices("xc7a35t", "xc7a35t", "xc7s25")
    assert chain.labels() == ("u1", "u2", "u3")
    assert len(chain) == 3
