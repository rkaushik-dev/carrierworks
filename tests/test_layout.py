import pytest

from fpga.catalog.flashparts import flash_part
from fpga.errors import ConfigurationError, NotFound, ValidationError
from fpga.flash.layout import FlashLayout, FlashRegion, multiboot_layout, single_image_layout
from fpga.measure import mib


def part():
    return flash_part("n25q128a13")


def test_a_region_knows_its_own_extent():
    region = FlashRegion("golden", 0, 0x400000, "golden")
    assert region.end == 0x400000
    assert region.contains(0)
    assert region.contains(0x3FFFFF)
    assert not region.contains(0x400000)
    assert region.holds(1000)
    assert not region.holds(0x400001)


def test_a_region_describes_itself():
    region = FlashRegion("update", 0x400000, mib(4), "update")
    assert region.describe() == "update 0x00400000 +4 MiB (update)"


@pytest.mark.parametrize(
    ("start", "length", "purpose"),
    [(-1, 100, "data"), (0, 0, "data"), (0, 100, "spare")],
)
def test_a_nonsense_region_is_refused(start, length, purpose):
    with pytest.raises(ValidationError):
        FlashRegion("bad", start, length, purpose)


def test_a_single_image_layout_rounds_up_to_whole_sectors():
    layout = single_image_layout(part(), 3_825_788)
    golden = layout.region("golden")
    assert golden.start == 0
    assert golden.length % part().sector_bytes == 0
    assert golden.length >= 3_825_788


def test_an_image_bigger_than_the_part_is_refused():
    with pytest.raises(ConfigurationError):
        single_image_layout(part(), mib(20))


def test_an_empty_image_is_refused():
    with pytest.raises(ValidationError):
        single_image_layout(part(), 0)


def test_a_multiboot_layout_puts_the_golden_image_at_zero():
    layout = multiboot_layout(part(), mib(4), mib(4))
    assert layout.region("golden").start == 0
    assert layout.region("update").start >= mib(4)


def test_the_update_address_can_be_pinned_because_the_golden_image_knows_it():
    layout = multiboot_layout(part(), mib(4), mib(4), update_start=0x800000)
    assert layout.region("update").start == 0x800000


def test_an_update_address_off_a_sector_boundary_is_refused():
    with pytest.raises(ConfigurationError):
        multiboot_layout(part(), mib(4), mib(4), update_start=0x800100)


def test_a_pair_that_does_not_fit_is_refused():
    with pytest.raises(ConfigurationError):
        multiboot_layout(part(), mib(8), mib(8), update_start=0xA00000)


def test_the_default_gap_leaves_a_spare_sector_between_the_images():
    layout = multiboot_layout(part(), mib(4), mib(4))
    golden = layout.region("golden")
    update = layout.region("update")
    assert update.start == golden.end + part().sector_bytes


def test_regions_cannot_overlap():
    with pytest.raises(ConfigurationError):
        FlashLayout(
            part=part(),
            regions=(
                FlashRegion("golden", 0, mib(4), "golden"),
                FlashRegion("update", mib(2), mib(4), "update"),
            ),
        )


def test_regions_cannot_share_a_sector_even_without_overlapping():
    spec = part()
    with pytest.raises(ConfigurationError) as caught:
        FlashLayout(
            part=spec,
            regions=(
                FlashRegion("golden", 0, spec.sector_bytes + 16, "golden"),
                FlashRegion("params", spec.sector_bytes + 32, 64, "data"),
            ),
        )
    assert "share a sector" in caught.value.message


def test_a_region_may_start_part_way_through_a_sector_if_it_owns_it():
    layout = FlashLayout(part=part(), regions=(FlashRegion("params", 0x100, 64, "data"),))
    assert layout.region("params").start == 0x100
    assert layout.sector_span("params") == (0, 0)


def test_a_region_off_the_end_of_the_part_is_refused():
    with pytest.raises(ConfigurationError):
        FlashLayout(part=part(), regions=(FlashRegion("golden", 0, mib(20), "golden"),))


def test_two_regions_cannot_share_a_name():
    with pytest.raises(ConfigurationError):
        FlashLayout(
            part=part(),
            regions=(
                FlashRegion("golden", 0, mib(1), "golden"),
                FlashRegion("golden", mib(2), mib(1), "update"),
            ),
        )


def test_an_empty_layout_is_refused():
    with pytest.raises(ConfigurationError):
        FlashLayout(part=part(), regions=())


def test_a_region_nobody_named_is_a_not_found():
    with pytest.raises(NotFound):
        single_image_layout(part(), mib(1)).region("update")


def test_the_layout_finds_the_region_at_an_address():
    layout = multiboot_layout(part(), mib(4), mib(4), update_start=0x800000)
    assert layout.region_at(0x10).name == "golden"
    assert layout.region_at(0x800010).name == "update"
    assert layout.region_at(0xF00000) is None


def test_used_and_free_add_up_to_the_part():
    layout = multiboot_layout(part(), mib(4), mib(4), update_start=0x800000)
    assert layout.used + layout.free == part().capacity_bytes


def test_the_sector_span_of_a_region_is_what_an_erase_needs():
    layout = multiboot_layout(part(), mib(4), mib(4), update_start=0x800000)
    assert layout.sector_span("update") == (128, 191)
    assert layout.sectors_for("update") == 64
    assert layout.sector_span("golden") == (0, 63)


def test_the_layout_describes_itself_in_order():
    layout = multiboot_layout(part(), mib(4), mib(4), update_start=0x800000)
    described = layout.describe()
    assert described.startswith("n25q128a13: golden 0x00000000")
    assert "update 0x00800000" in described
