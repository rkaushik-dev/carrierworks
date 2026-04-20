import json
from datetime import UTC, datetime, timedelta

import pytest
from click.testing import CliRunner

from boardsetup import artix, kintex
from fpga.cli import flash as flash_commands  # noqa: F401  (registers the commands)
from fpga.cli import images as image_commands  # noqa: F401  (registers the commands)
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.flash.plan import plan_write
from fpga.flash.wear import WearLedger
from fpga.jobs.runner import flash_layout
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory
from images import bitfile


def now():
    return datetime.now(UTC)


@pytest.fixture()
def bench(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    settings = BenchSettings(
        database_url=url,
        artifact_root=tmp_path / "artifacts",
        wear_ledger=tmp_path / "wear.json",
    )
    database = BenchDatabase(settings)
    database.create_schema()
    with database.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), now() - timedelta(days=1))
        inventory.add_board(unit, artix("bx-15"), now() - timedelta(days=1))
    monkeypatch.setenv("FPGA_ARTIFACT_ROOT", str(tmp_path / "artifacts"))
    monkeypatch.setenv("FPGA_WEAR_LEDGER", str(tmp_path / "wear.json"))
    return tmp_path, url


@pytest.fixture()
def run(bench):
    _, url = bench
    runner = CliRunner()

    def invoke(*args):
        return runner.invoke(cli, ["--database", url, *args], catch_exceptions=False)

    return invoke


@pytest.fixture()
def stored(run, bench):
    root, _ = bench
    (root / "radar.bit").write_bytes(bitfile())
    return json.loads(run("--json", "image", "upload", str(root / "radar.bit")).output)["digest"]


def wear_up(bench, sectors=None):
    root, _ = bench
    board = kintex("bx-14")
    ledger = WearLedger(root / "wear.json")
    ledger.restore(board.tag, sectors or {0: 90_000, 1: 400, 2: 7})
    ledger.save()
    return ledger


def test_the_layout_puts_golden_below_update(run):
    body = json.loads(run("--json", "flash", "layout", "bx-14").output)
    assert [entry["region"] for entry in body] == ["golden", "update"]
    assert body[0]["start"] == "0x00000000"
    assert body[1]["start"] == "0x00800000"


def test_a_smaller_part_gives_a_smaller_layout(run):
    body = json.loads(run("--json", "flash", "layout", "bx-15").output)
    assert body[1]["start"] == "0x00400000"


def test_a_board_nobody_has_is_an_error(run):
    result = run("flash", "layout", "bx-99")
    assert result.exit_code != 0
    assert "bx-99" in result.output


def test_a_write_plan_costs_the_erase_and_the_program(run, stored):
    result = run("flash", "plan", "bx-14", "--image", stored)
    assert result.exit_code == 0
    assert "verify   yes" in result.output
    assert "about" in result.output


def test_a_plan_without_verify_says_so(run, stored):
    result = run("flash", "plan", "bx-14", "--image", stored, "--no-verify")
    assert "verify   no" in result.output


def test_the_plan_steps_come_out_as_json(run, stored):
    body = json.loads(run("--json", "flash", "plan", "bx-14", "--image", stored).output)
    kinds = {entry["kind"] for entry in body}
    assert "program" in kinds
    assert body[0]["address"] == "0x00800000"


def test_the_golden_region_starts_at_zero(run, stored):
    body = json.loads(
        run("--json", "flash", "plan", "bx-14", "--image", stored, "--region", "golden").output
    )
    assert body[0]["address"] == "0x00000000"


def test_an_erase_plan_needs_no_image(run):
    result = run("flash", "plan", "bx-14", "--erase", "--region", "update")
    assert result.exit_code == 0
    assert "written  0 B" in result.output


def test_a_plan_with_neither_image_nor_erase_is_a_usage_error(run):
    result = run("flash", "plan", "bx-14")
    assert result.exit_code != 0
    assert "give an image to write" in result.output


def test_a_plan_for_an_image_nobody_stored_is_an_error(run):
    result = run("flash", "plan", "bx-14", "--image", "cd" * 32)
    assert result.exit_code != 0


def test_wear_for_the_bench_lists_every_board(run):
    body = json.loads(run("--json", "flash", "wear").output)
    assert {entry["asset"] for entry in body} == {"bx-14", "bx-15"}
    assert all(entry["erases"] == 0 for entry in body)


def test_a_worn_sector_shows_up_on_the_bench_listing(run, bench):
    wear_up(bench)
    body = json.loads(run("--json", "flash", "wear").output)
    worn = {entry["asset"]: entry["worn"] for entry in body}
    assert worn == {"bx-14": "yes", "bx-15": "no"}


def test_one_board_lists_its_hottest_sectors_first(run, bench):
    wear_up(bench)
    body = json.loads(run("--json", "flash", "wear", "bx-14").output)
    assert [entry["sector"] for entry in body] == [0, 1, 2]
    assert body[0]["used"] == "90.00%"
    assert body[0]["worn"] == "yes"


def test_the_listing_can_be_cut_to_the_worst_sector(run, bench):
    wear_up(bench)
    body = json.loads(run("--json", "flash", "wear", "bx-14", "--top", "1").output)
    assert len(body) == 1


def test_asking_for_no_sectors_is_an_error(run, bench):
    wear_up(bench)
    result = run("flash", "wear", "bx-14", "--top", "0")
    assert result.exit_code != 0


def test_the_command_costs_the_same_plan_the_runner_would(run, stored, bench):
    root, _ = bench
    from fpga.artifacts.registry import ArtifactStore

    image = ArtifactStore(root / "artifacts").read(stored)
    direct = plan_write(flash_layout(kintex("bx-14").flash_spec()), "update", image)
    body = json.loads(run("--json", "flash", "plan", "bx-14", "--image", stored).output)
    assert len(body) == len(direct.steps)
