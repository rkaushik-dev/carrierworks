import pytest

from fpga.catalog.flashparts import (
    FlashSpec,
    all_flash_parts,
    flash_for_jedec_id,
    flash_part,
    lookup_flash,
)
from fpga.errors import CatalogError, NotFound, ValidationError
from fpga.measure import mib


def test_a_part_is_found_by_name():
    spec = flash_part("N25Q128A13")
    assert spec.vendor == "micron"
    assert spec.capacity_bytes == mib(16)
    assert spec.sector_bytes == 65536


def test_an_unstocked_part_is_a_not_found():
    assert lookup_flash("at45db161") is None
    with pytest.raises(NotFound):
        flash_part("at45db161")


def test_a_part_is_found_by_what_it_answered():
    assert flash_for_jedec_id(0x20BA18).name == "n25q128a13"
    with pytest.raises(NotFound) as caught:
        flash_for_jedec_id(0x123456)
    assert caught.value.key == "0x123456"


def test_manufacturer_id_is_the_top_byte():
    assert flash_part("mx25l25635f").manufacturer_id == 0xC2


def test_three_byte_addressing_stops_at_sixteen_megabytes():
    assert flash_part("n25q128a13").address_bytes == 3
    assert flash_part("n25q256a11").address_bytes == 4
    assert flash_part("w25q64jv").address_bytes == 3


def test_geometry_divides_the_way_the_datasheet_says():
    spec = flash_part("n25q128a13")
    assert spec.sector_count == 256
    assert spec.subsectors_per_sector == 16


def test_sector_and_subsector_lookups():
    spec = flash_part("n25q128a13")
    assert spec.sector_of(0) == 0
    assert spec.sector_of(65535) == 0
    assert spec.sector_of(65536) == 1
    assert spec.sector_base(70000) == 65536
    assert spec.subsector_of(4097) == 1


def test_an_address_past_the_end_is_refused():
    spec = flash_part("w25q64jv")
    with pytest.raises(ValidationError):
        spec.sector_of(mib(8))
    with pytest.raises(ValidationError):
        spec.sector_of(-1)


def test_alignment_questions():
    spec = flash_part("n25q128a13")
    assert spec.is_sector_aligned(0x40000)
    assert not spec.is_sector_aligned(0x40100)
    assert spec.is_subsector_aligned(0x41000)
    assert not spec.is_subsector_aligned(0x41001)


def test_holds_is_about_capacity_not_addresses():
    spec = flash_part("w25q64jv")
    assert spec.holds(mib(8))
    assert not spec.holds(mib(9))


@pytest.mark.parametrize(
    ("address", "length", "pages"),
    [
        (0, 0, 0),
        (0, 1, 1),
        (0, 256, 1),
        (0, 257, 2),
        (255, 2, 2),
        (100, 156, 1),
        (100, 157, 2),
    ],
)
def test_page_spans_count_the_partial_first_page(address, length, pages):
    assert flash_part("n25q128a13").pages_spanned(address, length) == pages


def test_a_negative_length_is_a_bug():
    with pytest.raises(ValidationError):
        flash_part("n25q128a13").pages_spanned(0, -1)


def test_geometry_that_does_not_divide_is_refused_at_import_time():
    with pytest.raises(CatalogError):
        FlashSpec(
            name="bad",
            vendor="nobody",
            jedec_id=0x000001,
            capacity_bytes=1000,
            page_bytes=256,
            subsector_bytes=4096,
            sector_bytes=65536,
            page_program_us=1,
            subsector_erase_ms=1,
            sector_erase_ms=1,
            bulk_erase_s=1,
        )


def test_every_catalogued_part_has_sane_numbers():
    for spec in all_flash_parts():
        assert spec.capacity_bytes >= mib(8)
        assert spec.page_bytes == 256
        assert spec.subsector_erase_ms < spec.sector_erase_ms * 4
        assert spec.bulk_erase_s > 0
        assert spec.erase_cycles >= 100_000


def test_jedec_ids_are_unique():
    ids = [spec.jedec_id for spec in all_flash_parts()]
    assert len(set(ids)) == len(ids)
