from datetime import UTC, datetime, timedelta

import pytest

from fpga.errors import NotFound, ValidationError
from fpga.reports.baselines import BaselineBook, BuildReport, compare
from fpga.reports.timing import TimingSummary
from fpga.reports.utilization import SiteUsage, Utilization

NOW = datetime(2025, 11, 8, 3, 0, tzinfo=UTC)


def timing(wns=400, whs=100, failing=0):
    return TimingSummary(
        wns_ps=wns,
        tns_ps=0 if failing == 0 else -1000,
        setup_failing=failing,
        setup_total=100_000,
        whs_ps=whs,
        ths_ps=0,
        hold_failing=0,
        hold_total=100_000,
    )


def utilization(luts=45000, registers=61000):
    return Utilization(
        rows=(
            SiteUsage(name="Slice LUTs", used=luts, available=203800),
            SiteUsage(name="Slice Registers", used=registers, available=407600),
        )
    )


def report(digest="aa", design="radar_frontend", at=NOW, **changes):
    return BuildReport(
        digest=digest * 32,
        design=design,
        at=at,
        timing=changes.get("timing", timing()),
        utilization=changes.get("utilization", utilization()),
        revision=changes.get("revision", "9f2c11a"),
    )


def test_a_report_is_tied_to_its_image():
    built = report()
    assert built.short_digest == "aa" * 6
    assert built.closed
    assert built.describe().startswith("radar_frontend aaaaaaaaaaaa: ")


def test_a_report_needs_a_whole_digest_and_a_timezone():
    with pytest.raises(ValidationError):
        BuildReport(
            digest="abcdef",
            design="radar_frontend",
            at=NOW,
            timing=timing(),
            utilization=utilization(),
        )
    with pytest.raises(ValidationError):
        BuildReport(
            digest="ab" * 32,
            design="radar_frontend",
            at=datetime(2025, 11, 8, 3, 0),
            timing=timing(),
            utilization=utilization(),
        )


def test_two_identical_builds_do_not_regress():
    assert compare(report(), report(digest="bb")) == ()


def test_slack_noise_is_not_a_regression():
    worse = report(digest="bb", timing=timing(wns=360))
    assert compare(report(), worse) == ()


def test_a_real_slack_drop_is_reported():
    worse = report(digest="bb", timing=timing(wns=120, whs=30))
    found = compare(report(), worse)
    assert found == ("worst slack fell from 0.100 to 0.030 ns",)


def test_a_hold_slack_drop_is_reported_too():
    worse = report(digest="bb", timing=timing(whs=-40))
    found = compare(report(), worse)
    assert any("slack fell" in line for line in found)
    assert any("does not close timing" in line for line in found)


def test_new_failing_endpoints_are_reported():
    worse = report(digest="bb", timing=timing(wns=-100, failing=17))
    found = compare(report(), worse)
    assert any("failing endpoints went from 0 to 17" in line for line in found)


def test_small_logic_growth_is_not_a_regression():
    grown = report(digest="bb", utilization=utilization(luts=47000))
    assert compare(report(), grown) == ()


def test_real_logic_growth_is_reported():
    grown = report(digest="bb", utilization=utilization(luts=90000))
    found = compare(report(), grown)
    assert any(line.startswith("Slice LUTs went from") for line in found)


def test_a_row_the_other_report_does_not_have_is_skipped():
    thin = report(digest="bb", utilization=Utilization(rows=()))
    assert compare(report(), thin) == ()


def test_two_designs_cannot_be_compared():
    with pytest.raises(ValidationError):
        compare(report(), report(digest="bb", design="beamformer"))


def test_the_first_closing_build_becomes_the_baseline():
    book = BaselineBook()
    first = book.record(report())
    baseline = book.baseline("radar_frontend")
    assert baseline is not None
    assert baseline.digest == first.digest


def test_a_build_that_did_not_close_does_not_become_the_baseline():
    book = BaselineBook()
    book.record(report(timing=timing(wns=-100, failing=4)))
    assert book.baseline("radar_frontend") is None


def test_later_builds_do_not_move_the_baseline():
    book = BaselineBook()
    first = book.record(report())
    book.record(report(digest="bb", at=NOW + timedelta(days=1)))
    baseline = book.baseline("radar_frontend")
    assert baseline is not None
    assert baseline.digest == first.digest


def test_the_baseline_can_be_pinned_to_a_build_somebody_liked():
    book = BaselineBook()
    book.record(report())
    later = book.record(report(digest="bb", at=NOW + timedelta(days=1)))
    book.set_baseline("radar_frontend", later.digest)
    baseline = book.baseline("radar_frontend")
    assert baseline is not None
    assert baseline.digest == later.digest


def test_pinning_a_build_from_another_design_is_refused():
    book = BaselineBook()
    book.record(report())
    other = book.record(report(digest="bb", design="beamformer"))
    with pytest.raises(ValidationError):
        book.set_baseline("radar_frontend", other.digest)


def test_pinning_a_build_nobody_recorded_is_a_not_found():
    with pytest.raises(NotFound):
        BaselineBook().set_baseline("radar_frontend", "cc" * 32)


def test_regressions_are_reported_against_the_baseline():
    book = BaselineBook()
    book.record(report())
    worse = book.record(report(digest="bb", timing=timing(wns=100, whs=20)))
    assert book.regressions(worse)


def test_a_build_does_not_regress_against_itself():
    book = BaselineBook()
    first = book.record(report())
    assert book.regressions(first) == ()


def test_a_design_with_no_baseline_has_no_regressions():
    book = BaselineBook()
    only = book.record(report(timing=timing(wns=-100, failing=4)))
    assert book.regressions(only) == ()


def test_the_book_keeps_every_report_per_design():
    book = BaselineBook()
    book.record(report())
    book.record(report(digest="bb", at=NOW + timedelta(hours=1)))
    book.record(report(digest="cc", design="beamformer"))
    assert book.designs() == ("beamformer", "radar_frontend")
    assert len(book.reports("radar_frontend")) == 2
    assert len(book) == 3
    latest = book.latest("radar_frontend")
    assert latest is not None
    assert latest.digest == "bb" * 32
