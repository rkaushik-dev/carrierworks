import pytest

from fpga.bitstream.packets import SYNC_WORD, command_names, parse_packets
from fpga.catalog.flashparts import flash_part
from fpga.errors import ValidationError
from fpga.flash.layout import multiboot_layout, single_image_layout
from fpga.flash.multiboot import (
    assemble_multiboot,
    golden_with_jump,
    read_warm_boot_address,
    warm_boot_prefix,
)
from fpga.measure import kib, mib


def layout():
    return multiboot_layout(flash_part("n25q128a13"), mib(4), mib(4), update_start=0x800000)


def test_the_prefix_is_the_sequence_from_the_user_guide():
    words = [
        int.from_bytes(warm_boot_prefix(0x800000)[index * 4 : index * 4 + 4], "big")
        for index in range(8)
    ]
    assert words == [
        0xFFFFFFFF,
        SYNC_WORD,
        0x20000000,
        0x30020001,
        0x00800000,
        0x30008001,
        0x0000000F,
        0x20000000,
    ]


def test_the_prefix_parses_as_packets_that_say_what_it_does():
    packets = parse_packets(warm_boot_prefix(0x800000))
    writes = [packet.register_name for packet in packets if packet.is_write]
    assert writes == ["wbstar", "cmd"]
    assert command_names(packets) == ("iprog",)


def test_the_address_reads_back_out_of_the_prefix():
    assert read_warm_boot_address(warm_boot_prefix(0x800000)) == 0x800000
    assert read_warm_boot_address(warm_boot_prefix(0)) == 0


def test_an_ordinary_image_is_not_mistaken_for_a_jump():
    assert read_warm_boot_address(b"\x00" * 64) is None
    assert read_warm_boot_address(b"") is None


def test_a_nearly_right_sequence_is_still_not_a_jump():
    broken = bytearray(warm_boot_prefix(0x800000))
    broken[24:28] = (0x0000000D).to_bytes(4, "big")
    assert read_warm_boot_address(bytes(broken)) is None


def test_an_address_wider_than_the_register_is_refused():
    with pytest.raises(ValidationError):
        warm_boot_prefix(0x20000000)
    with pytest.raises(ValidationError):
        warm_boot_prefix(-1)


def test_the_assembled_image_starts_with_the_jump():
    whole = assemble_multiboot(layout(), b"\xa1" * kib(64), b"\xb2" * kib(64))
    assert read_warm_boot_address(whole) == 0x800000


def test_the_golden_image_follows_the_jump_immediately():
    golden = b"\xa1" * kib(64)
    whole = assemble_multiboot(layout(), golden, b"\xb2" * kib(4))
    assert whole[32 : 32 + len(golden)] == golden


def test_the_update_image_lands_at_its_address():
    update = b"\xb2" * kib(64)
    whole = assemble_multiboot(layout(), b"\xa1" * kib(64), update)
    assert whole[0x800000 : 0x800000 + len(update)] == update
    assert len(whole) == 0x800000 + len(update)


def test_the_gap_between_them_is_erased_rather_than_zeroed():
    whole = assemble_multiboot(layout(), b"\xa1" * kib(4), b"\xb2" * kib(4))
    assert whole[kib(4) + 32 : kib(8)] == b"\xff" * (kib(4) - 32)


def test_the_fill_can_be_changed_for_a_part_that_erases_to_zero():
    whole = assemble_multiboot(layout(), b"\xa1" * kib(4), b"\xb2" * kib(4), fill=0x00)
    assert whole[kib(4) + 32 : kib(8)] == b"\x00" * (kib(4) - 32)


def test_a_golden_image_that_does_not_leave_room_for_the_jump_is_refused():
    small = multiboot_layout(flash_part("n25q128a13"), kib(64), kib(64), update_start=0x200000)
    with pytest.raises(ValidationError) as caught:
        assemble_multiboot(small, b"\xa1" * kib(64), b"\xb2" * kib(4))
    assert caught.value.details["room"] == kib(64)


def test_an_update_image_that_does_not_fit_its_region_is_refused():
    with pytest.raises(ValidationError):
        assemble_multiboot(layout(), b"\xa1" * kib(4), b"\xb2" * mib(5))


def test_a_layout_whose_golden_image_is_not_at_zero_is_refused():
    from fpga.flash.layout import FlashLayout, FlashRegion

    moved = FlashLayout(
        part=flash_part("n25q128a13"),
        regions=(
            FlashRegion("golden", mib(1), mib(1), "golden"),
            FlashRegion("update", mib(4), mib(1), "update"),
        ),
    )
    with pytest.raises(ValidationError):
        assemble_multiboot(moved, b"\xa1" * kib(4), b"\xb2" * kib(4))


def test_a_layout_with_no_update_region_cannot_multiboot():
    from fpga.errors import NotFound

    with pytest.raises(NotFound):
        assemble_multiboot(single_image_layout(flash_part("n25q128a13"), mib(1)), b"\x01", b"\x02")


def test_the_golden_image_can_be_programmed_on_its_own_with_its_jump():
    golden = b"\xa1" * kib(4)
    head = golden_with_jump(layout(), golden)
    assert read_warm_boot_address(head) == 0x800000
    assert head[32:] == golden
