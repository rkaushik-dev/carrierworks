from datetime import UTC, datetime, timedelta

from boardsetup import kintex
from fpga.artifacts.registry import ArtifactRecord
from fpga.flash.layout import single_image_layout
from fpga.flash.plan import plan_write
from fpga.flash.wear import WearLedger
from fpga.inventory.state import BoardState
from fpga.jobs.gates import check_job
from fpga.jobs.models import ProgramJob
from fpga.leases.models import Lease
from fpga.measure import mib

NOW = datetime(2025, 10, 6, 10, 0, tzinfo=UTC)
DIGEST = "cd" * 32


def board(**rest):
    return kintex("bx-14", **rest)


def record(**changes):
    fields = {
        "digest": DIGEST,
        "design": "radar_frontend",
        "device": "xc7k325t",
        "length": 11_000_000,
        "kind": "full",
        "channel": "qual",
    }
    fields.update(changes)
    return ArtifactRecord(**fields)


def job(**changes):
    fields = {
        "id": "job-00001",
        "board": "bx-14",
        "digest": DIGEST,
        "mode": "jtag",
        "requested_by": "team-a",
        "created_at": NOW,
    }
    fields.update(changes)
    return ProgramJob(**fields)


def lease(holder="team-a"):
    return Lease(
        id="lease-00001",
        board="bx-14",
        holder=holder,
        purpose="capture",
        taken_at=NOW - timedelta(minutes=5),
        expires_at=NOW + timedelta(hours=1),
    )


def leased():
    return BoardState(state="leased", since=NOW - timedelta(minutes=5))


def check(**changes):
    live = changes.pop("job", job())
    fields = {
        "board": board(),
        "record": record(),
        "state": leased(),
        "lease": lease(),
        "timing_closed": True,
    }
    fields.update(changes)
    return check_job(live, **fields)


def test_a_clean_jtag_job_is_allowed():
    report = check()
    assert report.allowed
    assert report.describe() == "job-00001 may run"


def test_a_job_on_a_board_nobody_holds_is_refused():
    report = check(lease=None)
    assert "nobody holds a lease on that board" in report.blockers


def test_a_job_on_somebody_else_s_board_is_refused():
    report = check(lease=lease(holder="team-b"))
    assert "the board is leased to team-b" in report.blockers


def test_a_job_on_a_faulted_board_is_refused():
    state = BoardState(state="faulted", since=NOW, reason="chain")
    report = check(state=state)
    assert "the board is faulted" in report.blockers


def test_a_job_while_the_board_is_already_programming_is_allowed():
    state = BoardState(state="programming", since=NOW)
    assert check(state=state).allowed


def test_an_image_for_another_device_is_refused():
    report = check(record=record(device="xc7a100t"))
    assert "the image is for xc7a100t and the board carries xc7k325t" in report.blockers


def test_an_image_longer_than_the_device_configuration_is_refused():
    report = check(record=record(length=200_000_000))
    assert any("longer than this device" in blocker for blocker in report.blockers)


def test_a_dev_image_can_be_loaded_over_jtag():
    assert check(record=record(channel="dev")).allowed


def test_a_dev_image_cannot_be_written_to_flash():
    report = check(job=job(mode="flash-update"), record=record(channel="dev"))
    assert any("takes qual or golden images" in blocker for blocker in report.blockers)


def test_a_qualified_image_can_go_in_the_update_region():
    assert check(job=job(mode="flash-update")).allowed


def test_only_a_golden_image_goes_in_the_golden_region():
    refused = check(job=job(mode="flash-golden"))
    assert any("takes golden images" in blocker for blocker in refused.blockers)
    assert check(job=job(mode="flash-golden"), record=record(channel="golden")).allowed


def test_a_partial_image_cannot_be_written_to_flash():
    report = check(job=job(mode="flash-update"), record=record(kind="partial"))
    assert "a partial image cannot be written to flash" in report.blockers


def test_a_partial_image_can_still_be_loaded_over_jtag():
    assert check(record=record(kind="partial")).allowed


def test_an_image_bigger_than_the_flash_is_refused():
    report = check(job=job(mode="flash-update"), record=record(length=mib(20)))
    assert "the image is bigger than the flash on this board" in report.blockers


def test_a_flash_write_needs_a_timing_report():
    report = check(job=job(mode="flash-update"), timing_closed=None)
    assert "no timing report has been read for this image" in report.blockers


def test_a_design_that_missed_timing_does_not_go_in_flash():
    report = check(job=job(mode="flash-update"), timing_closed=False)
    assert "the design did not close timing" in report.blockers


def test_timing_is_not_checked_for_a_jtag_load():
    assert check(timing_closed=None).allowed


def test_the_timing_gate_can_be_turned_off_for_a_site_that_does_not_build():
    report = check(job=job(mode="flash-update"), timing_closed=None, require_timing=False)
    assert report.allowed


def test_a_worn_flash_does_not_get_the_golden_image():
    ledger = WearLedger("unused.json")
    ledger.restore("bx-14", {0: 95_000})
    report = check(
        job=job(mode="flash-golden"),
        record=record(channel="golden"),
        wear=ledger,
    )
    assert "near the end of its erase life" in report.blockers[0]


def test_a_fresh_flash_takes_the_golden_image():
    ledger = WearLedger("unused.json")
    ledger.record(
        "bx-14",
        plan_write(single_image_layout(board().flash_spec(), mib(4)), "golden", b"\x01" * 4096),
    )
    report = check(
        job=job(mode="flash-golden"),
        record=record(channel="golden"),
        wear=ledger,
    )
    assert report.allowed


def test_a_cable_that_cannot_write_flash_stops_a_flash_job():
    slow = board(adapter_model="arm-usb-tiny-h")
    report = check(job=job(mode="flash-update"), board=slow)
    assert any("cannot write flash" in blocker for blocker in report.blockers)


def test_a_board_with_no_cable_cannot_take_a_flash_job():
    report = check(job=job(mode="flash-update"), board=board(serial=None))
    assert "no cable is on that board" in report.blockers


def test_every_blocker_is_reported_at_once():
    report = check(
        job=job(mode="flash-golden"),
        record=record(device="xc7a100t", channel="dev", kind="partial"),
        lease=None,
        timing_closed=False,
    )
    assert len(report.blockers) >= 5
    assert report.describe().startswith("job-00001 refused: ")
