import json
from datetime import UTC, datetime, timedelta

import pytest
from click.testing import CliRunner

from boardsetup import artix, kintex
from fpga.cli import chain as chain_commands  # noqa: F401  (registers the commands)
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.inventory.chains import chain_for
from fpga.jtag.bits import BitVector
from fpga.jtag.discover import scan_bits_for
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory


def now():
    return datetime.now(UTC)


@pytest.fixture()
def url(tmp_path):
    address = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    settings = BenchSettings(database_url=address, artifact_root=tmp_path / "artifacts")
    database = BenchDatabase(settings)
    database.create_schema()
    with database.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), now() - timedelta(days=1))
        inventory.add_board(unit, artix("bx-15"), now() - timedelta(days=1))
    return address


@pytest.fixture()
def run(url):
    runner = CliRunner()

    def invoke(*args):
        return runner.invoke(cli, ["--database", url, *args], catch_exceptions=False)

    return invoke


def healthy(board):
    return str(scan_bits_for(chain_for(board)))


def test_the_expected_chain_lists_the_controller_in_front_of_the_part(run):
    body = json.loads(run("--json", "chain", "show", "bx-14").output)
    assert [entry["label"] for entry in body] == ["sysctl", "u1"]
    assert body[1]["device"] == "xc7k325t"


def test_a_carrier_with_no_controller_is_one_position(run):
    body = json.loads(run("--json", "chain", "show", "bx-15").output)
    assert [entry["label"] for entry in body] == ["u1"]


def test_a_scan_against_the_simulator_matches(run):
    result = run("chain", "scan", "bx-14")
    assert result.exit_code == 0
    assert "chain matches" in result.output


def test_a_matching_scan_reports_what_answered(run):
    body = json.loads(run("--json", "chain", "scan", "bx-14").output)
    assert body["ok"] is True
    assert "xc7k325t" in body["found"]
    assert body["moved_to"] is None


def test_a_scan_that_matches_leaves_the_board_where_it_was(run):
    run("chain", "scan", "bx-14", "--apply")
    assert json.loads(run("--json", "boards", "show", "bx-14").output)["state"] == "available"


def test_a_capture_of_all_ones_reads_as_a_cable(run):
    result = run("--json", "chain", "diagnose", "bx-14", "1" * 96)
    body = json.loads(result.output)
    assert body["ok"] is False
    assert body["would_move_to"] == "faulted"


def test_the_wrong_part_on_the_header_reads_as_maintenance(run):
    swapped = chain_for(artix("bx-15"))
    body = json.loads(
        run("--json", "chain", "diagnose", "bx-14", str(scan_bits_for(swapped))).output
    )
    assert body["ok"] is False
    assert body["would_move_to"] == "maintenance"
    assert body["wiring"] is None


def test_a_diagnose_of_a_good_capture_is_quiet(run):
    good = healthy(kintex("bx-14"))
    body = json.loads(run("--json", "chain", "diagnose", "bx-14", good).output)
    assert body["ok"] is True
    assert body["would_move_to"] is None


def test_a_capture_can_come_out_of_a_file(run, tmp_path):
    path = tmp_path / "capture.txt"
    path.write_text(healthy(kintex("bx-14")) + "\n", encoding="utf-8")
    result = run("chain", "diagnose", "bx-14", str(path), "--file")
    assert "chain matches" in result.output


def test_a_capture_that_is_not_bits_is_an_error(run):
    result = run("chain", "diagnose", "bx-14", "not bits at all")
    assert result.exit_code != 0


def test_the_underlying_check_agrees_with_the_command(run):
    board = kintex("bx-14")
    assert BitVector.parse(healthy(board)) == scan_bits_for(chain_for(board))
