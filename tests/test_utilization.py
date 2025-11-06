import pytest

from fpga.catalog.devices import device_for
from fpga.errors import ValidationError
from fpga.reports.utilization import SiteUsage, Utilization, parse_utilization_report

REPORT = """
Utilization Design Information
Design : radar_frontend

1. Slice Logic
--------------

+----------------------------+-------+-------+-----------+-------+
|          Site Type         |  Used | Fixed | Available | Util% |
+----------------------------+-------+-------+-----------+-------+
| Slice LUTs                 | 45123 |     0 |    203800 | 22.14 |
| Slice Registers            | 61234 |     0 |    407600 | 15.02 |
+----------------------------+-------+-------+-----------+-------+

3. Memory
---------

+-------------------+------+-------+-----------+-------+
|     Site Type     | Used | Fixed | Available | Util% |
+-------------------+------+-------+-----------+-------+
| Block RAM Tile    |  145 |     0 |       445 | 32.58 |
+-------------------+------+-------+-----------+-------+

4. DSP
------

+-----------+------+-------+-----------+-------+
| Site Type | Used | Fixed | Available | Util% |
+-----------+------+-------+-----------+-------+
| DSPs      |  210 |     0 |       840 | 25.00 |
+-----------+------+-------+-----------+-------+
"""

NEWER = """
+----------------------------+-------+-------+------------+-----------+-------+
|          Site Type         |  Used | Fixed | Prohibited | Available | Util% |
+----------------------------+-------+-------+------------+-----------+-------+
| CLB LUTs                   | 12000 |     0 |          0 |    203800 |  5.89 |
| CLB Registers              | 18000 |     0 |          0 |    407600 |  4.42 |
+----------------------------+-------+-------+------------+-----------+-------+
"""


def test_the_four_numbers_come_out():
    used = parse_utilization_report(REPORT)
    assert used.luts == 45123
    assert used.registers == 61234
    assert used.block_ram == 145
    assert used.dsp == 210


def test_the_design_name_comes_through():
    assert parse_utilization_report(REPORT).design == "radar_frontend"
    assert parse_utilization_report(NEWER).design == ""


def test_the_header_row_is_not_a_site():
    names = [row.name for row in parse_utilization_report(REPORT).rows]
    assert "Site Type" not in names
    assert len(names) == 4


def test_a_newer_report_with_a_prohibited_column_still_parses():
    used = parse_utilization_report(NEWER)
    assert used.luts == 12000
    assert used.registers == 18000
    assert used.block_ram == 0


def test_percentages_are_recomputed_rather_than_trusted():
    row = parse_utilization_report(REPORT).row("luts")
    assert row is not None
    assert row.percent == pytest.approx(22.14, abs=0.01)


def test_the_busiest_row_is_the_one_nearest_full():
    busiest = parse_utilization_report(REPORT).busiest()
    assert busiest is not None
    assert busiest.name == "Block RAM Tile"


def test_rows_over_a_threshold_come_back():
    over = parse_utilization_report(REPORT).over(25.0)
    assert [row.name for row in over] == ["Block RAM Tile", "DSPs"]


def test_a_design_fits_the_part_it_was_built_for():
    used = parse_utilization_report(REPORT)
    assert used.fits(device_for("xc7k325t"))


def test_the_same_design_does_not_fit_a_smaller_part():
    used = parse_utilization_report(REPORT)
    assert not used.fits(device_for("xc7a100t"))


def test_a_small_design_fits_a_small_part():
    used = parse_utilization_report(NEWER)
    assert used.fits(device_for("xc7a100t"))


def test_a_report_with_no_tables_is_refused():
    with pytest.raises(ValidationError):
        parse_utilization_report("Utilization Design Information\nnothing here\n")


def test_asking_for_a_kind_nobody_has_is_a_bug():
    with pytest.raises(ValidationError):
        parse_utilization_report(REPORT).row("transceivers")


def test_a_row_using_more_than_exists_is_refused():
    with pytest.raises(ValidationError):
        SiteUsage(name="Slice LUTs", used=10, available=5)


def test_negative_counts_are_refused():
    with pytest.raises(ValidationError):
        SiteUsage(name="Slice LUTs", used=-1, available=5)


def test_a_row_of_nothing_available_reads_as_zero_percent():
    assert SiteUsage(name="Unused", used=0, available=0).percent == 0.0


def test_a_row_describes_itself():
    row = parse_utilization_report(REPORT).row("dsp")
    assert row is not None
    assert row.describe() == "DSPs 210/840 (25.0%)"


def test_the_report_describes_itself():
    described = parse_utilization_report(REPORT).describe()
    assert described.startswith("radar_frontend: 45123 luts, 61234 registers")
    assert "busiest Block RAM Tile" in described


def test_an_empty_utilization_describes_itself_too():
    assert Utilization(rows=()).describe() == "nothing used"
    assert Utilization(rows=()).busiest() is None
