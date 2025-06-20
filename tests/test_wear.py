import pytest

from fpga.catalog.flashparts import flash_part
from fpga.errors import ValidationError
from fpga.flash.layout import multiboot_layout
from fpga.flash.plan import plan_erase, plan_write
from fpga.flash.wear import SectorWear, WearLedger
from fpga.measure import kib, mib


def part():
    return flash_part("n25q128a13")


def layout():
    return multiboot_layout(part(), mib(4), mib(4), update_start=0x800000)


def ledger(tmp_path):
    return WearLedger(tmp_path / "wear.json")


def test_a_write_counts_one_erase_per_sector_it_touches(tmp_path):
    book = ledger(tmp_path)
    touched = book.record("bx-14", plan_write(layout(), "update", b"\x01" * (kib(64) + 1)))
    assert touched == 2
    assert book.erases("bx-14", 128) == 1
    assert book.erases("bx-14", 129) == 1
    assert book.erases("bx-14", 130) == 0


def test_erases_accumulate_over_programming_runs(tmp_path):
    book = ledger(tmp_path)
    for _ in range(3):
        book.record("bx-14", plan_write(layout(), "update", b"\x01" * kib(4)))
    assert book.erases("bx-14", 128) == 3
    assert book.total("bx-14") == 3


def test_sector_zero_takes_the_wear_because_every_image_starts_there(tmp_path):
    book = ledger(tmp_path)
    for _ in range(10):
        book.record("bx-14", plan_write(layout(), "golden", b"\x01" * kib(4)))
        book.record("bx-14", plan_write(layout(), "update", b"\x01" * kib(4)))
    assert book.erases("bx-14", 0) == 10
    assert book.erases("bx-14", 128) == 10
    assert book.erases("bx-14", 64) == 0


def test_a_whole_region_erase_counts_every_sector(tmp_path):
    book = ledger(tmp_path)
    book.record("bx-14", plan_erase(layout(), "golden"))
    assert book.total("bx-14") == 64
    assert book.erases("bx-14", 63) == 1


def test_subsector_erases_inside_one_sector_all_count_against_it(tmp_path):
    book = ledger(tmp_path)
    book.record("bx-14", plan_write(layout(), "update", b"\x01" * kib(12), use_subsectors=True))
    assert book.erases("bx-14", 128) == 3


def test_boards_are_counted_separately(tmp_path):
    book = ledger(tmp_path)
    book.record("bx-14", plan_write(layout(), "update", b"\x01" * kib(4)))
    book.record("bx-15", plan_write(layout(), "update", b"\x01" * kib(4)))
    assert book.boards() == ("bx-14", "bx-15")
    assert book.total("bx-15") == 1


def test_asset_tags_are_normalised_so_one_board_is_one_row(tmp_path):
    book = ledger(tmp_path)
    book.record("BX-0014", plan_write(layout(), "update", b"\x01" * kib(4)))
    book.record("bx-14", plan_write(layout(), "update", b"\x01" * kib(4)))
    assert book.boards() == ("bx-14",)
    assert book.total("bx-14") == 2


def test_the_ledger_survives_a_restart(tmp_path):
    book = ledger(tmp_path)
    book.record("bx-14", plan_write(layout(), "update", b"\x01" * kib(4)))
    book.save()
    reopened = WearLedger(tmp_path / "wear.json")
    assert reopened.erases("bx-14", 128) == 1
    assert reopened.boards() == ("bx-14",)


def test_a_ledger_with_no_file_yet_is_empty(tmp_path):
    assert ledger(tmp_path).boards() == ()
    assert ledger(tmp_path).total("bx-14") == 0


def test_wear_is_reported_most_worn_first(tmp_path):
    book = ledger(tmp_path)
    for _ in range(4):
        book.record("bx-14", plan_write(layout(), "golden", b"\x01" * kib(4)))
    book.record("bx-14", plan_write(layout(), "update", b"\x01" * kib(4)))
    wear = book.wear("bx-14", part())
    assert [entry.sector for entry in wear] == [0, 128]
    assert wear[0].erases == 4
    assert wear[0].describe() == "sector 0: 4 of 100000 erases"


def test_only_the_hottest_sectors_come_back_when_asked(tmp_path):
    book = ledger(tmp_path)
    book.record("bx-14", plan_erase(layout(), "golden"))
    assert len(book.hottest("bx-14", part(), limit=3)) == 3
    with pytest.raises(ValidationError):
        book.hottest("bx-14", part(), limit=0)


def test_a_fresh_board_has_nothing_worn(tmp_path):
    book = ledger(tmp_path)
    book.record("bx-14", plan_write(layout(), "update", b"\x01" * kib(4)))
    assert book.worn_sectors("bx-14", part()) == ()
    assert not book.is_worn("bx-14", part())


def test_a_sector_past_four_fifths_of_its_rating_is_worn():
    entry = SectorWear(sector=0, erases=80_000, rated=100_000)
    assert entry.worn
    assert entry.remaining == 20_000
    assert entry.fraction == pytest.approx(0.8)


def test_a_sector_below_the_line_is_not_worn():
    assert not SectorWear(sector=0, erases=79_999, rated=100_000).worn


def test_a_sector_past_its_rating_has_nothing_left():
    entry = SectorWear(sector=0, erases=120_000, rated=100_000)
    assert entry.remaining == 0
    assert entry.worn


def test_a_negative_sector_is_a_bug(tmp_path):
    with pytest.raises(ValidationError):
        ledger(tmp_path).erases("bx-14", -1)


def test_a_worn_board_is_reported_from_the_ledger(tmp_path):
    book = ledger(tmp_path)
    book.restore("bx-14", {0: 90_000, 1: 12})
    assert book.is_worn("bx-14", part())
    assert [entry.sector for entry in book.worn_sectors("bx-14", part())] == [0]


def test_restored_history_carries_on_being_counted(tmp_path):
    book = ledger(tmp_path)
    book.restore("bx-14", {0: 400})
    book.record("bx-14", plan_write(layout(), "golden", b"\x01" * kib(4)))
    assert book.erases("bx-14", 0) == 401


def test_restoring_nonsense_is_refused(tmp_path):
    with pytest.raises(ValidationError):
        ledger(tmp_path).restore("bx-14", {-1: 4})
    with pytest.raises(ValidationError):
        ledger(tmp_path).restore("bx-14", {0: -4})
