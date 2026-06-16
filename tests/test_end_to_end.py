"""One day on the bench, from racking a board to signing off a release.

Every other test in here holds one thing still and pushes on another. This one
does not: it runs the whole path the lab actually walks, through the command
line, in the order it happens, against one database on disk. It exists because
three defects have got through the unit tests by living in the seams: a lease
that the job gate could not see, a report that was stored against the wrong
digest, and a bundle that named a file it had not written.

It is deliberately one long test rather than fifteen small ones. The value is
in the order, and a fixture that set up step nine would be asserting that step
eight worked rather than running it.
"""

import json
from datetime import UTC, datetime

import pytest
from click.testing import CliRunner

from fpga.cli import audit as audit_commands  # noqa: F401  (registers the commands)
from fpga.cli import chain as chain_commands  # noqa: F401  (registers the commands)
from fpga.cli import flash as flash_commands  # noqa: F401  (registers the commands)
from fpga.cli import images as image_commands  # noqa: F401  (registers the commands)
from fpga.cli import jobs as job_commands  # noqa: F401  (registers the commands)
from fpga.cli import leases as lease_commands  # noqa: F401  (registers the commands)
from fpga.cli import power as power_commands  # noqa: F401  (registers the commands)
from fpga.cli import queue as queue_commands  # noqa: F401  (registers the commands)
from fpga.cli import status as status_command  # noqa: F401  (registers the commands)
from fpga.cli import thermal as thermal_commands  # noqa: F401  (registers the commands)
from fpga.cli import upkeep as upkeep_commands  # noqa: F401  (registers the commands)
from fpga.cli import usage as usage_commands  # noqa: F401  (registers the commands)
from fpga.cli import worker as worker_commands  # noqa: F401  (registers the commands)
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from images import TIMING, UTILIZATION, bitfile

BENCH = {
    "site": "lab-a",
    "boards": [
        {
            "asset": "bx-31",
            "model": "rx-carrier-b",
            "part": "XC7K325T-2FFG900I",
            "flash": "n25q128a13",
            "location": "rack-7/shelf-01/slot-1",
            "adapter": {"serial": "210308FA0001", "model": "jtag-hs2"},
            "pdu": "pdu-e/outlet-01",
            "slots": [{"name": "fmc0", "connector": "fmc-hpc", "fitted": "fmc-adc-4ch-14b"}],
        },
        {
            "asset": "bx-32",
            "model": "tx-carrier-a",
            "part": "XC7A100T-2FGG484I",
            "flash": "w25q64jv",
            "location": "rack-7/shelf-01/slot-2",
            "adapter": {"serial": "210308FA0002", "model": "jtag-hs3"},
            "pdu": "pdu-e/outlet-02",
        },
    ],
}


def now():
    return datetime.now(UTC)


@pytest.fixture()
def lab(tmp_path, monkeypatch):
    """A bench with nothing in it and somewhere to put files."""
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    BenchDatabase(
        BenchSettings(
            database_url=url,
            artifact_root=tmp_path / "artifacts",
            journal_root=tmp_path / "journal",
            wear_ledger=tmp_path / "wear.json",
        )
    ).create_schema()
    monkeypatch.setenv("FPGA_ARTIFACT_ROOT", str(tmp_path / "artifacts"))
    monkeypatch.setenv("FPGA_JOURNAL_ROOT", str(tmp_path / "journal"))
    monkeypatch.setenv("FPGA_WEAR_LEDGER", str(tmp_path / "wear.json"))
    (tmp_path / "bench.json").write_text(json.dumps(BENCH), encoding="utf-8")
    (tmp_path / "radar.bit").write_bytes(bitfile(frames=64))
    (tmp_path / "timing.rpt").write_text(TIMING, encoding="utf-8")
    (tmp_path / "usage.rpt").write_text(UTILIZATION, encoding="utf-8")
    runner = CliRunner()

    def run(*args):
        result = runner.invoke(cli, ["--database", url, *args], catch_exceptions=False)
        assert result.exit_code == 0, f"{args} exited {result.exit_code}: {result.output}"
        return result

    def ask(*args):
        return json.loads(run("--json", *args).output)

    def refuse(*args):
        """Run something the bench should turn down, and hand back what it said."""
        result = runner.invoke(cli, ["--database", url, *args], catch_exceptions=False)
        assert result.exit_code != 0, f"{args} was allowed: {result.output}"
        return result.output

    return tmp_path, run, ask, refuse


def test_a_day_on_the_bench(lab):
    root, run, ask, refuse = lab

    # The boards arrive and are racked from the file the person wrote.
    added = ask("boards", "import", str(root / "bench.json"))
    assert added["added"] == ["bx-31", "bx-32"]
    assert {entry["asset"] for entry in ask("boards", "list")} == {"bx-31", "bx-32"}

    # Nobody has calibrated the one with a converter on it, so it is overdue.
    assert [entry["asset"] for entry in ask("upkeep", "due")] == ["bx-31"]
    run("upkeep", "done", "bx-31", "--by", "sam", "--kind", "calibration")
    assert ask("upkeep", "due") == []

    # The chain answers with what the inventory says is on the header.
    scanned = ask("chain", "scan", "bx-31")
    assert scanned["ok"] is True
    assert "xc7k325t" in scanned["found"]

    # The rack controller starts reporting temperatures.
    run("thermal", "record", "bx-31", "58.5C")
    run("thermal", "record", "bx-32", "51.0C")
    states = {entry["asset"]: entry["state"] for entry in ask("thermal", "status")}
    assert states == {"bx-31": "normal", "bx-32": "normal"}

    # The build system uploads an image and the two reports together.
    uploaded = ask(
        "--json",
        "image",
        "upload",
        str(root / "radar.bit"),
        "--channel",
        "qual",
        "--timing",
        str(root / "timing.rpt"),
        "--utilization",
        str(root / "usage.rpt"),
    )
    digest = uploaded["digest"]
    assert uploaded["design"] == "radar_frontend"
    assert uploaded["timing_closed"] is True

    # Somebody asks for a board by what it has to do, and the pass hands one over.
    run(
        "queue",
        "submit",
        "--holder",
        "team-a",
        "--purpose",
        "capture run",
        "--needs",
        "adc-4ch",
    )
    dispatched = ask("queue", "dispatch")
    assert [entry["board"] for entry in dispatched["assigned"]] == ["bx-31"]
    assert ask("boards", "show", "bx-31")["held_by"] == "team-a"

    # The job runs against the simulator and the board comes back leased.
    run("job", "submit", "--board", "bx-31", "--image", digest, "--by", "team-a")
    ran = ask("job", "run")
    assert ran["state"] == "passed"
    assert ask("boards", "show", "bx-31")["state"] == "leased"

    # A flash write costs what the plan said it would.
    plan = ask("flash", "plan", "bx-31", "--image", digest)
    assert {entry["kind"] for entry in plan} >= {"erase", "program", "verify"}

    # The lease is nearly up, and the holder gets a line in the trail about it.
    run("lease", "renew", "lease-00001", "--minutes", "1")
    warned = ask("lease", "expiring", "--remind")
    assert [entry["lease"] for entry in warned] == ["lease-00001"]
    assert [line["action"] for line in ask("audit", "--action", "expiring")] == ["expiring"]

    # Everything about the image goes into a bundle that checks out.
    bundled = ask(
        "image", "bundle", digest, "--into", str(root / "release"), "--approved-by", "kim"
    )
    assert bundled["jobs"] == 1
    assert bundled["reports"] == 1
    assert "matches its manifest" in run("image", "check-bundle", bundled["root"]).output

    # Golden is refused, and the refusal names what is still missing. One
    # passing load and no record of where the image came from is not enough
    # to put an image in the half of the flash a board boots from.
    refused = refuse("image", "promote", digest, "golden", "--approve", "kim")
    assert "nothing recorded where this image came from" in refused
    assert "clean loads" in refused

    # The board goes back, and the bench reads as quiet again.
    run("lease", "release", "lease-00001", "--reason", "capture finished")
    state = ask("status")
    assert state["available"] == 2
    assert state["leases_held"] == 0
    assert state["calibration_overdue"] == []

    # And the day shows up in the usage report.
    summary = ask("usage", "summary", "--days", "1")
    assert summary["leases"] == 1
    assert summary["busiest"] == "bx-31"


def test_a_worker_picks_up_what_the_day_left_behind(lab):
    root, run, ask, _ = lab
    run("boards", "import", str(root / "bench.json"))
    digest = ask(
        "--json",
        "image",
        "upload",
        str(root / "radar.bit"),
        "--channel",
        "qual",
        "--timing",
        str(root / "timing.rpt"),
        "--utilization",
        str(root / "usage.rpt"),
    )["digest"]

    # A request waiting, and a job queued behind the lease it will get.
    run(
        "queue",
        "submit",
        "--holder",
        "team-b",
        "--purpose",
        "overnight",
        "--needs",
        "device=xc7k325t",
    )
    worked = ask("worker", "run")
    assert worked["assigned"] == ["req-0001 -> bx-31"]

    run("job", "submit", "--board", "bx-31", "--image", digest, "--by", "team-b")
    second = ask("worker", "run")
    assert [entry["state"] for entry in second["ran"]] == ["passed"]
    assert ask("job", "list")[0]["state"] == "passed"
