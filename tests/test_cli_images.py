import json
from datetime import UTC, datetime

import pytest
from click.testing import CliRunner

from fpga.bitstream.bitfile import build_bitfile
from fpga.bitstream.packets import SYNC_WORD, type1, type2, words_to_bytes
from fpga.cli import images as image_commands  # noqa: F401  (registers the commands)
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase

WHEN = datetime(2026, 2, 18, 8, 0, tzinfo=UTC)

TIMING = """
Design Timing Summary
| WNS(ns) | TNS(ns) | TNS Failing | TNS Total | WHS(ns) | THS(ns) | THS Failing | THS Total |
| 0.412 | 0.000 | 0 | 118423 | 0.137 | 0.000 | 0 | 118423 |
"""

FAILING = """
| WNS(ns) | TNS(ns) | TNS Failing | TNS Total | WHS(ns) | THS(ns) | THS Failing | THS Total |
| -0.311 | -14.200 | 96 | 118423 | 0.137 | 0.000 | 0 | 118423 |
"""

UTILIZATION = """
| Slice LUTs      | 45123 |     0 |    203800 | 22.14 |
| Slice Registers | 61234 |     0 |    407600 | 15.02 |
"""


def bitfile(frames=16):
    words = [
        0xFFFFFFFF,
        SYNC_WORD,
        type1(opcode=2, register=12, word_count=1),
        0x03651093,
        type1(opcode=2, register=2, word_count=0),
        type2(opcode=2, word_count=frames),
        *range(frames),
        type1(opcode=2, register=4, word_count=1),
        5,
    ]
    return build_bitfile(
        design="radar_frontend;Version=2024.1",
        part="7k325tffg900",
        date="2026/02/17",
        time="02:00:00",
        data=words_to_bytes(words),
    )


@pytest.fixture()
def workspace(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    settings = BenchSettings(database_url=url, artifact_root=tmp_path / "artifacts")
    BenchDatabase(settings).create_schema()
    (tmp_path / "radar.bit").write_bytes(bitfile())
    (tmp_path / "other.bit").write_bytes(bitfile(frames=20))
    (tmp_path / "timing.rpt").write_text(TIMING, encoding="utf-8")
    (tmp_path / "failing.rpt").write_text(FAILING, encoding="utf-8")
    (tmp_path / "usage.rpt").write_text(UTILIZATION, encoding="utf-8")
    return tmp_path, url


@pytest.fixture()
def run(workspace, monkeypatch):
    root, url = workspace
    monkeypatch.setenv("FPGA_ARTIFACT_ROOT", str(root / "artifacts"))
    runner = CliRunner()

    def invoke(*args):
        return runner.invoke(cli, ["--database", url, *args], catch_exceptions=False)

    return invoke


def test_an_image_is_stored_from_a_file(run, workspace):
    root, _ = workspace
    result = run("image", "upload", str(root / "radar.bit"))
    assert result.exit_code == 0
    assert "radar_frontend" in result.output
    assert "stored in dev" in result.output


def test_the_reports_can_come_up_with_it(run, workspace):
    root, _ = workspace
    result = run(
        "image",
        "upload",
        str(root / "radar.bit"),
        "--timing",
        str(root / "timing.rpt"),
        "--utilization",
        str(root / "usage.rpt"),
    )
    assert "timing closed" in result.output


def test_a_build_that_missed_timing_says_so(run, workspace):
    root, _ = workspace
    result = run(
        "image",
        "upload",
        str(root / "radar.bit"),
        "--timing",
        str(root / "failing.rpt"),
        "--utilization",
        str(root / "usage.rpt"),
    )
    assert "did not close" in result.output


def test_a_file_that_is_not_a_bit_file_is_an_error(run, workspace):
    root, _ = workspace
    (root / "junk.bit").write_bytes(b"not a bitstream")
    result = run("image", "upload", str(root / "junk.bit"))
    assert result.exit_code != 0
    assert "bit file" in result.output


def test_images_are_listed(run, workspace):
    root, _ = workspace
    run("image", "upload", str(root / "radar.bit"))
    result = run("image", "list")
    assert "DIGEST" in result.output
    assert "radar_frontend" in result.output


def test_the_listing_can_be_narrowed_by_channel(run, workspace):
    root, _ = workspace
    run("image", "upload", str(root / "radar.bit"))
    run("image", "upload", str(root / "other.bit"), "--channel", "qual")
    body = json.loads(run("--json", "image", "list", "--channel", "qual").output)
    assert len(body) == 1
    assert body[0]["channel"] == "qual"


def test_showing_an_image_says_what_is_known(run, workspace):
    root, _ = workspace
    digest = json.loads(run("--json", "image", "upload", str(root / "radar.bit")).output)["digest"]
    result = run("image", "show", digest)
    assert "radar_frontend" in result.output
    assert "timing   not read" in result.output
    assert "loads    0 passed, 0 failed" in result.output


def test_showing_an_image_as_json_carries_the_slack(run, workspace):
    root, _ = workspace
    digest = json.loads(
        run(
            "--json",
            "image",
            "upload",
            str(root / "radar.bit"),
            "--timing",
            str(root / "timing.rpt"),
            "--utilization",
            str(root / "usage.rpt"),
        ).output
    )["digest"]
    body = json.loads(run("--json", "image", "show", digest).output)
    assert body["timing_closed"] is True
    assert body["worst_slack_ps"] == 137


def test_an_image_nobody_stored_is_an_error(run):
    result = run("image", "show", "ab" * 32)
    assert result.exit_code != 0
    assert "no artifact known" in result.output


def test_promotion_refuses_without_the_evidence(run, workspace):
    root, _ = workspace
    digest = json.loads(run("--json", "image", "upload", str(root / "radar.bit")).output)["digest"]
    result = run("image", "promote", digest, "qual")
    assert result.exit_code != 0
    assert "timing" in result.output


def test_the_baseline_is_shown_and_pinned(run, workspace):
    root, _ = workspace
    first = json.loads(
        run(
            "--json",
            "image",
            "upload",
            str(root / "radar.bit"),
            "--timing",
            str(root / "timing.rpt"),
            "--utilization",
            str(root / "usage.rpt"),
        ).output
    )["digest"]
    shown = run("image", "baseline", "radar_frontend")
    assert first[:12] in shown.output
    second = json.loads(
        run(
            "--json",
            "image",
            "upload",
            str(root / "other.bit"),
            "--timing",
            str(root / "timing.rpt"),
            "--utilization",
            str(root / "usage.rpt"),
        ).output
    )["digest"]
    pinned = run("image", "baseline", "radar_frontend", "--pin", second)
    assert second[:12] in pinned.output


def test_a_design_with_no_baseline_is_an_error(run):
    result = run("image", "baseline", "beamformer")
    assert result.exit_code != 0
    assert "no baseline known as beamformer" in result.output


def test_regressions_read_clean_against_the_baseline(run, workspace):
    root, _ = workspace
    digest = json.loads(
        run(
            "--json",
            "image",
            "upload",
            str(root / "radar.bit"),
            "--timing",
            str(root / "timing.rpt"),
            "--utilization",
            str(root / "usage.rpt"),
        ).output
    )["digest"]
    result = run("image", "regressions", digest)
    assert "nothing worse than the baseline" in result.output
