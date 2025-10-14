import pytest

from fpga.catalog.flashparts import flash_part
from fpga.errors import ValidationError
from fpga.flash.layout import multiboot_layout
from fpga.flash.plan import plan_write
from fpga.jobs.flasher import FakeFlash, FlashOutcome, SpiFlashDriver, VerifyFailed, write_image
from fpga.measure import kib, mib

IMAGE = bytes(range(256)) * 20


def part():
    return flash_part("n25q128a13")


def layout():
    return multiboot_layout(part(), mib(4), mib(4), update_start=0x800000)


def driver():
    return FakeFlash(spec=part())


def test_the_fake_satisfies_the_driver_protocol():
    assert isinstance(driver(), SpiFlashDriver)


def test_a_fresh_part_reads_as_erased():
    assert driver().read(0, 16) == b"\xff" * 16


def test_the_part_answers_with_its_own_id():
    assert driver().read_id() == 0x20BA18


def test_an_image_writes_and_verifies():
    flash = driver()
    plan = plan_write(layout(), "update", IMAGE)
    outcome = write_image(flash, plan, IMAGE, start=0x800000)
    assert outcome.verified
    assert outcome.bytes_written == len(IMAGE)
    assert flash.read(0x800000, len(IMAGE)) == IMAGE


def test_the_erases_and_pages_are_counted():
    flash = driver()
    plan = plan_write(layout(), "update", b"\x01" * kib(64))
    outcome = write_image(flash, plan, b"\x01" * kib(64), start=0x800000)
    assert outcome.erases == 1
    assert outcome.pages == 256
    assert flash.erases == 1
    assert flash.writes == 256


def test_nothing_outside_the_region_is_touched():
    flash = driver()
    flash.storage[0:16] = b"\xa5" * 16
    plan = plan_write(layout(), "update", IMAGE)
    write_image(flash, plan, IMAGE, start=0x800000)
    assert flash.read(0, 16) == b"\xa5" * 16


def test_programming_without_an_erase_produces_neither_image():
    flash = driver()
    flash.program(0, b"\x0f")
    flash.program(0, b"\xf0")
    assert flash.read(0, 1) == b"\x00"


def test_an_erase_puts_the_bits_back():
    flash = driver()
    flash.program(0, b"\x00")
    flash.erase(0, 4096)
    assert flash.read(0, 1) == b"\xff"


def test_a_second_write_over_the_same_region_still_verifies():
    flash = driver()
    first = plan_write(layout(), "update", IMAGE)
    write_image(flash, first, IMAGE, start=0x800000)
    other = bytes(reversed(IMAGE))
    second = plan_write(layout(), "update", other)
    write_image(flash, second, other, start=0x800000)
    assert flash.read(0x800000, len(other)) == other


def test_a_part_that_does_not_hold_what_it_was_given_fails_verification():
    flash = driver()
    flash.weak_bytes = {0x800010}
    plan = plan_write(layout(), "update", IMAGE)
    with pytest.raises(VerifyFailed) as caught:
        write_image(flash, plan, IMAGE, start=0x800000)
    assert caught.value.details["region"] == "update"
    assert not caught.value.retryable


def test_verification_can_be_left_out_and_then_nothing_is_checked():
    flash = driver()
    flash.weak_bytes = {0x800010}
    plan = plan_write(layout(), "update", IMAGE, verify=False)
    outcome = write_image(flash, plan, IMAGE, start=0x800000)
    assert not outcome.verified


def test_a_plan_for_another_image_is_refused():
    flash = driver()
    plan = plan_write(layout(), "update", IMAGE)
    with pytest.raises(ValidationError) as caught:
        write_image(flash, plan, IMAGE + b"\x00", start=0x800000)
    assert caught.value.details["planned"] == len(IMAGE)


def test_a_plan_for_another_part_is_refused():
    flash = FakeFlash(spec=flash_part("w25q64jv"))
    plan = plan_write(layout(), "update", b"\x01" * kib(4))
    with pytest.raises(ValidationError) as caught:
        write_image(flash, plan, b"\x01" * kib(4), start=0x800000)
    assert caught.value.details["expected"] == "0x20ba18"


def test_a_page_write_cannot_cross_a_page_boundary():
    flash = driver()
    with pytest.raises(ValidationError):
        flash.program(0xF0, b"\x01" * 300)
    with pytest.raises(ValidationError):
        flash.program(0xF0, b"\x01" * 200)


def test_an_erase_starts_on_a_subsector():
    flash = driver()
    with pytest.raises(ValidationError):
        flash.erase(0x100, 4096)


def test_an_address_past_the_end_is_refused():
    flash = driver()
    with pytest.raises(ValidationError):
        flash.read(mib(16), 4)


def test_the_outcome_describes_itself():
    outcome = FlashOutcome(region="update", bytes_written=4096, erases=1, pages=16, verified=True)
    assert outcome.describe() == "update: 4096 bytes in 16 pages, 1 erases, verified"


def test_the_golden_region_writes_at_address_zero():
    flash = driver()
    plan = plan_write(layout(), "golden", IMAGE)
    write_image(flash, plan, IMAGE, start=0)
    assert flash.read(0, len(IMAGE)) == IMAGE
