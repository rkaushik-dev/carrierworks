import pytest

from fpga.errors import ValidationError
from fpga.reports.timing import TimingSummary, parse_timing_report

REPORT = """
Design Timing Summary
Design : radar_frontend
---------------------------------------------------------------------------

| WNS(ns) | TNS(ns) | TNS Failing Endpoints | TNS Total Endpoints | WHS(ns) | THS(ns) | THS Failing Endpoints | THS Total Endpoints |
| 0.147   | 0.000   | 0                     | 118423              | 0.021   | 0.000   | 0                     | 118423              |

Clock Summary
Clock            Waveform(ns)       Period(ns)      Frequency(MHz)
-----            ------------       ----------      --------------
sys_clk          {0.000 5.000}      10.000          100.000
adc_clk          {0.000 2.000}      4.000           250.000
"""

FAILING = """
Design Timing Summary
| WNS(ns) | TNS(ns)  | TNS Failing Endpoints | TNS Total Endpoints | WHS(ns) | THS(ns) | THS Failing Endpoints | THS Total Endpoints |
| -0.412  | -18.220  | 143                   | 118423              | 0.021   | 0.000   | 0                     | 118423              |
"""

HOLD_FAILING = """
| WNS(ns) | TNS(ns) | TNS Failing Endpoints | TNS Total Endpoints | WHS(ns) | THS(ns)  | THS Failing Endpoints | THS Total Endpoints |
| 0.900   | 0.000   | 0                     | 118423              | -0.031  | -1.402   | 47                    | 118423              |
"""


def test_a_clean_report_reads_as_closed():
    summary = parse_timing_report(REPORT)
    assert summary.met
    assert summary.setup_met
    assert summary.hold_met
    assert summary.failing_endpoints == 0


def test_the_slacks_come_through_as_picoseconds():
    summary = parse_timing_report(REPORT)
    assert summary.wns_ps == 147
    assert summary.whs_ps == 21
    assert summary.tns_ps == 0


def test_the_design_name_is_picked_up_when_the_tool_prints_it():
    assert parse_timing_report(REPORT).design == "radar_frontend"
    assert parse_timing_report(FAILING).design == ""


def test_the_clocks_are_read_with_their_periods():
    summary = parse_timing_report(REPORT)
    assert [clock.name for clock in summary.clocks] == ["sys_clk", "adc_clk"]
    assert summary.clocks[0].period_ps == 10_000
    fastest = summary.fastest_clock()
    assert fastest is not None
    assert fastest.name == "adc_clk"
    assert fastest.describe() == "adc_clk 250.000 MHz"


def test_a_report_with_no_clock_table_has_no_clocks():
    summary = parse_timing_report(FAILING)
    assert summary.clocks == ()
    assert summary.fastest_clock() is None


def test_a_setup_failure_is_seen():
    summary = parse_timing_report(FAILING)
    assert not summary.met
    assert not summary.setup_met
    assert summary.hold_met
    assert summary.wns_ps == -412
    assert summary.setup_failing == 143


def test_a_hold_failure_is_seen_even_when_setup_looks_fine():
    summary = parse_timing_report(HOLD_FAILING)
    assert summary.setup_met
    assert not summary.hold_met
    assert not summary.met
    assert summary.whs_ps == -31


def test_the_worst_slack_is_the_worse_of_the_two():
    assert parse_timing_report(HOLD_FAILING).worst_slack_ps == -31
    assert parse_timing_report(REPORT).worst_slack_ps == 21


def test_failing_endpoints_add_up_across_both():
    summary = parse_timing_report(HOLD_FAILING)
    assert summary.failing_endpoints == 47


def test_a_report_with_no_summary_is_refused():
    with pytest.raises(ValidationError):
        parse_timing_report("Design Timing Summary\nnothing to see here\n")


def test_the_first_summary_row_wins_when_a_report_holds_several():
    doubled = REPORT + FAILING
    assert parse_timing_report(doubled).wns_ps == 147


def test_a_row_without_the_leading_bar_still_parses():
    plain = "0.147 | 0.000 | 0 | 100 | 0.021 | 0.000 | 0 | 100"
    assert parse_timing_report(plain).wns_ps == 147


def test_a_summary_that_claims_more_failures_than_endpoints_is_refused():
    with pytest.raises(ValidationError):
        TimingSummary(
            wns_ps=-100,
            tns_ps=-100,
            setup_failing=10,
            setup_total=5,
            whs_ps=0,
            ths_ps=0,
            hold_failing=0,
            hold_total=5,
        )


def test_negative_endpoint_counts_are_refused():
    with pytest.raises(ValidationError):
        TimingSummary(
            wns_ps=0,
            tns_ps=0,
            setup_failing=-1,
            setup_total=5,
            whs_ps=0,
            ths_ps=0,
            hold_failing=0,
            hold_total=5,
        )


def test_a_summary_describes_itself():
    assert parse_timing_report(REPORT).describe() == (
        "radar_frontend closed: wns 0.147 ns, whs 0.021 ns, 0 failing"
    )
    assert "did not close" in parse_timing_report(FAILING).describe()
