import pytest

from fpga.errors import ValidationError
from fpga.jtag.bits import BitVector


def test_length_is_carried_so_padding_survives():
    assert len(BitVector.zeros(6)) == 6
    assert len(BitVector.zeros(8)) == 8
    assert BitVector.zeros(6) != BitVector.zeros(8)


def test_ones_fills_the_whole_width():
    assert BitVector.ones(6).value == 0b111111
    assert BitVector.ones(0) == BitVector(0, 0)


def test_a_value_wider_than_its_length_is_refused():
    with pytest.raises(ValidationError):
        BitVector(0b1000, 3)
    with pytest.raises(ValidationError):
        BitVector(1, 0)
    with pytest.raises(ValidationError):
        BitVector(-1, 4)
    with pytest.raises(ValidationError):
        BitVector(0, -1)


def test_parsing_reads_the_left_hand_bit_as_most_significant():
    assert BitVector.parse("1010").value == 0b1010
    assert BitVector.parse("0001").length == 4
    assert BitVector.parse("").length == 0


def test_parsing_tolerates_grouping():
    assert BitVector.parse("1010_0011") == BitVector.parse("10100011")
    assert BitVector.parse(" 11 00 ") == BitVector.parse("1100")


@pytest.mark.parametrize("text", ["1012", "abc", "0x0f"])
def test_parsing_junk_is_refused(text):
    with pytest.raises(ValidationError):
        BitVector.parse(text)


def test_printing_puts_the_most_significant_bit_on_the_left():
    assert str(BitVector.parse("0011")) == "0011"
    assert str(BitVector(0b1, 4)) == "0001"
    assert str(BitVector(0, 0)) == ""
    assert repr(BitVector.parse("0011")) == "BitVector('0011')"


def test_bit_zero_is_the_one_that_shifts_first():
    vector = BitVector.parse("1000")
    assert vector.bit(0) == 0
    assert vector.bit(3) == 1


def test_reading_off_the_end_is_refused():
    with pytest.raises(ValidationError):
        BitVector.parse("1010").bit(4)
    with pytest.raises(ValidationError):
        BitVector.parse("1010").bit(-1)


def test_bytes_go_out_little_end_first():
    assert BitVector(0x0201, 16).to_bytes() == b"\x01\x02"
    assert BitVector.parse("1").to_bytes() == b"\x01"


def test_bytes_come_back_the_same_way():
    assert BitVector.from_bytes(b"\x01\x02") == BitVector(0x0201, 16)
    assert BitVector.from_bytes(b"\xff", 6) == BitVector(0b111111, 6)


def test_asking_for_more_bits_than_the_buffer_holds_is_refused():
    with pytest.raises(ValidationError):
        BitVector.from_bytes(b"\xff", 9)


def test_bytes_round_trip_when_the_length_is_kept():
    original = BitVector.parse("101100111")
    assert BitVector.from_bytes(original.to_bytes(), len(original)) == original


def test_concatenation_puts_the_first_operand_out_first():
    head = BitVector.parse("11")
    tail = BitVector.parse("00")
    joined = head + tail
    assert len(joined) == 4
    assert joined.bit(0) == 1
    assert joined.bit(3) == 0
    assert str(joined) == "0011"


def test_concatenating_nothing_changes_nothing():
    vector = BitVector.parse("1011")
    assert vector + BitVector(0, 0) == vector
    assert BitVector(0, 0) + vector == vector


def test_slicing_counts_from_the_shifting_end():
    vector = BitVector.parse("11110000")
    assert str(vector.slice(0, 4)) == "0000"
    assert str(vector.slice(4, 4)) == "1111"
    assert vector.slice(2, 0) == BitVector(0, 0)


def test_slicing_off_the_end_is_refused():
    with pytest.raises(ValidationError):
        BitVector.parse("1010").slice(2, 3)
    with pytest.raises(ValidationError):
        BitVector.parse("1010").slice(-1, 2)


def test_reversing_turns_the_vector_round():
    assert str(BitVector.parse("1000").reversed()) == "0001"
    assert BitVector.parse("1010").reversed().reversed() == BitVector.parse("1010")
    assert BitVector(0, 0).reversed() == BitVector(0, 0)


def test_chunking_starts_at_the_shifting_end_and_leaves_the_short_piece_last():
    pieces = BitVector.parse("110100101").chunks(4)
    assert [str(piece) for piece in pieces] == ["0101", "1010", "1"]
    assert sum(len(piece) for piece in pieces) == 9


def test_chunking_a_vector_shorter_than_the_chunk_gives_one_piece():
    assert len(BitVector.parse("101").chunks(8)) == 1


def test_a_chunk_size_of_nothing_is_refused():
    with pytest.raises(ValidationError):
        BitVector.parse("1010").chunks(0)


def test_an_empty_vector_chunks_to_nothing():
    assert BitVector(0, 0).chunks(4) == ()
