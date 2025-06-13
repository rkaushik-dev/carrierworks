import pytest

from fpga.catalog.flashparts import flash_part
from fpga.errors import NotFound, ValidationError
from fpga.flash.layout import FlashLayout, FlashRegion, multiboot_layout, single_image_layout
from fpga.flash.plan import plan_erase, plan_write
from fpga.measure import kib, mib


def part():
    return flash_part("n25q128a13")


def layout():
    return multiboot_layout(part(), mib(4), mib(4), update_start=0x800000)


def test_a_write_erases_programs_and_verifies_in_that_order():
    plan = plan_write(layout(), "update", b"\xa5" * kib(8))
    kinds = [step.kind for step in plan.steps]
    assert kinds[0] == "erase"
    assert kinds.count("erase") == 1
    assert "program" in kinds
    assert kinds[-1] == "verify"
    assert kinds.index("program") < kinds.index("verify")


def test_the_erase_covers_every_sector_the_write_touches():
    plan = plan_write(layout(), "update", b"\xa5" * (kib(64) + 1))
    erases = plan.steps_of("erase")
    assert len(erases) == 2
    assert [step.address for step in erases] == [0x800000, 0x810000]


def test_the_erase_starts_at_the_sector_the_region_begins_in():
    small = FlashLayout(part=part(), regions=(FlashRegion("params", 0x100, kib(4), "data"),))
    plan = plan_write(small, "params", b"\x01" * 100)
    assert plan.steps_of("erase")[0].address == 0


def test_subsector_erases_are_offered_for_small_writes():
    small = FlashLayout(part=part(), regions=(FlashRegion("params", 0, kib(8), "data"),))
    plan = plan_write(small, "params", b"\x01" * kib(5), use_subsectors=True)
    erases = plan.steps_of("erase")
    assert len(erases) == 2
    assert all(step.length == 4096 for step in erases)


def test_pages_are_split_at_page_boundaries_not_every_page_bytes():
    small = FlashLayout(part=part(), regions=(FlashRegion("params", 0x180, kib(4), "data"),))
    plan = plan_write(small, "params", b"\x01" * 600)
    programs = plan.steps_of("program")
    assert [step.length for step in programs] == [128, 256, 216]
    assert [step.address for step in programs] == [0x180, 0x200, 0x300]


def test_an_aligned_image_writes_whole_pages():
    plan = plan_write(layout(), "update", b"\x01" * 768)
    assert [step.length for step in plan.steps_of("program")] == [256, 256, 256]


def test_every_byte_of_the_image_gets_programmed():
    data = b"\x5a" * (kib(9) + 37)
    plan = plan_write(layout(), "update", data)
    assert plan.programmed_bytes == len(data)
    assert plan.image_bytes == len(data)


def test_verification_reads_the_image_back_in_chunks():
    data = b"\x5a" * (kib(192) + 10)
    plan = plan_write(layout(), "update", data)
    reads = plan.steps_of("verify")
    assert [step.length for step in reads] == [65536, 65536, 65536, 10]
    assert sum(step.length for step in reads) == len(data)


def test_verification_can_be_turned_off():
    plan = plan_write(layout(), "update", b"\x01" * kib(4), verify=False)
    assert not plan.verifies
    assert plan.steps_of("verify") == ()


def test_the_estimate_adds_up_from_the_datasheet_numbers():
    plan = plan_write(layout(), "update", b"\x01" * kib(64), verify=False)
    erase_ms = part().sector_erase_ms
    program_ms = max(1, part().page_program_us // 1000) * 256
    assert plan.total_ms == erase_ms + program_ms
    assert plan.total_seconds == -(-plan.total_ms // 1000)


def test_a_bigger_image_takes_longer():
    small = plan_write(layout(), "update", b"\x01" * kib(64))
    large = plan_write(layout(), "update", b"\x01" * mib(1))
    assert large.total_ms > small.total_ms * 10


def test_an_image_that_does_not_fit_the_region_is_refused():
    with pytest.raises(ValidationError) as caught:
        plan_write(layout(), "update", b"\x01" * mib(5))
    assert caught.value.details["region"] == "update"


def test_programming_nothing_is_refused():
    with pytest.raises(ValidationError):
        plan_write(layout(), "update", b"")


def test_an_erase_plan_clears_the_whole_region():
    plan = plan_erase(layout(), "golden")
    assert plan.erased_bytes == mib(4)
    assert plan.programmed_bytes == 0
    assert plan.image_bytes == 0
    assert len(plan.steps_of("erase")) == 64


def test_an_erase_plan_can_use_subsectors_too():
    small = FlashLayout(part=part(), regions=(FlashRegion("params", 0, kib(8), "data"),))
    plan = plan_erase(small, "params", use_subsectors=True)
    assert len(plan.steps_of("erase")) == 2


def test_the_plan_describes_itself_for_an_operator():
    plan = plan_write(single_image_layout(part(), mib(1)), "golden", b"\x01" * kib(128))
    described = plan.describe()
    assert described.startswith("golden on n25q128a13: 2 erases, 512 page writes")
    assert described.endswith("s")


def test_a_step_describes_itself():
    step = plan_write(layout(), "update", b"\x01" * 256).steps_of("program")[0]
    assert step.describe() == "program 0x00800000 256 B ~1ms"


def test_erasing_a_region_nobody_named_is_refused():
    with pytest.raises(NotFound):
        plan_erase(layout(), "scratch")
