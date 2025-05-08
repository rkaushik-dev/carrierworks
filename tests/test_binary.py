import pytest

from fpga.bitstream.binary import (
    byte_swap,
    pad_to,
    reverse_bits,
    split_pages,
    to_bin,
    to_flash_image,
    trim_erased,
    word_swap,
)
from fpga.bitstream.bitfile import build_bitfile
from fpga.bitstream.image import read_bitstream
from fpga.bitstream.packets import SYNC_WORD, type1, words_to_bytes
from fpga.errors import ValidationError

PAYLOAD = words_to_bytes(
    [
        0xFFFFFFFF,
        SYNC_WORD,
        type1(opcode=2, register=12, word_count=1),
        0x03631093,
        type1(opcode=2, register=4, word_count=1),
        5,
    ]
)


def image():
    return read_bitstream(
        build_bitfile(
            design="beamformer",
            part="7a100tfgg484",
            date="2025/05/09",
            time="10:11:12",
            data=PAYLOAD,
        )
    )


def test_bit_reversal_turns_each_byte_round():
    assert reverse_bits(b"\x01") == b"\x80"
    assert reverse_bits(b"\xf0") == b"\x0f"
    assert reverse_bits(b"\xaa") == b"\x55"


def test_bit_reversal_leaves_the_byte_order_alone():
    assert reverse_bits(b"\x01\x02") == b"\x80\x40"


def test_bit_reversal_is_its_own_inverse():
    assert reverse_bits(reverse_bits(PAYLOAD)) == PAYLOAD


def test_byte_swapping_turns_pairs_round():
    assert byte_swap(b"\x01\x02\x03\x04") == b"\x02\x01\x04\x03"
    assert word_swap(b"\x01\x02\x03\x04") == b"\x04\x03\x02\x01"


def test_byte_swapping_is_its_own_inverse():
    assert word_swap(word_swap(PAYLOAD)) == PAYLOAD


def test_a_swap_that_does_not_divide_the_image_is_refused():
    with pytest.raises(ValidationError):
        word_swap(b"\x01\x02\x03")
    with pytest.raises(ValidationError):
        byte_swap(b"\x01\x02", width=1)


def test_the_bin_form_is_the_image_without_the_header():
    assert to_bin(image()) == PAYLOAD


def test_the_flash_form_is_bit_reversed_by_default():
    assert to_flash_image(image()) == reverse_bits(PAYLOAD)


def test_the_swap_can_be_turned_off_for_the_carriers_that_do_it_themselves():
    assert to_flash_image(image(), swap=False) == PAYLOAD


def test_padding_fills_with_the_erased_value():
    assert pad_to(b"\x01\x02", 5) == b"\x01\x02\xff\xff\xff"
    assert pad_to(b"\x01", 3, fill=0x00) == b"\x01\x00\x00"


def test_padding_to_the_length_it_already_is_changes_nothing():
    assert pad_to(b"\x01\x02", 2) == b"\x01\x02"


def test_padding_down_is_refused_because_it_would_be_a_truncation():
    with pytest.raises(ValidationError) as caught:
        pad_to(b"\x01\x02\x03", 2)
    assert caught.value.details["have"] == 3


@pytest.mark.parametrize(("length", "fill"), [(-1, 0xFF), (4, 256), (4, -1)])
def test_nonsense_padding_is_refused(length, fill):
    with pytest.raises(ValidationError):
        pad_to(b"\x01", length, fill=fill)


def test_trimming_takes_the_erased_tail_off():
    assert trim_erased(b"\x01\x02\xff\xff") == b"\x01\x02"
    assert trim_erased(b"\xff\xff") == b""
    assert trim_erased(b"\x01\x02") == b"\x01\x02"


def test_trimming_leaves_erased_bytes_in_the_middle_alone():
    assert trim_erased(b"\x01\xff\x02\xff") == b"\x01\xff\x02"


def test_padding_and_trimming_round_trip():
    assert trim_erased(pad_to(PAYLOAD, len(PAYLOAD) + 64)) == PAYLOAD


def test_pages_come_out_the_right_size_with_a_short_last_one():
    pages = split_pages(b"\x01" * 700, 256)
    assert [len(page) for page in pages] == [256, 256, 188]
    assert b"".join(pages) == b"\x01" * 700


def test_splitting_nothing_gives_no_pages():
    assert split_pages(b"", 256) == ()


def test_a_page_size_of_nothing_is_refused():
    with pytest.raises(ValidationError):
        split_pages(b"\x01", 0)


def test_a_flash_image_round_trips_through_padding_and_swapping():
    flash = to_flash_image(image())
    padded = pad_to(flash, len(flash) + 100)
    assert reverse_bits(trim_erased(padded)) == PAYLOAD
