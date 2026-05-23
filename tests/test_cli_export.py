import json
from datetime import UTC, datetime

import pytest
from click.testing import CliRunner

from fpga.bitstream.binary import reverse_bits
from fpga.bitstream.mcs import flatten, read_mcs
from fpga.cli import images as image_commands  # noqa: F401  (registers the commands)
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.persistence.unit import BenchDatabase
from images import bitfile

WHEN = datetime(2026, 5, 26, 9, 0, tzinfo=UTC)


@pytest.fixture()
def workspace(tmp_path, monkeypatch):
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    BenchDatabase(
        BenchSettings(database_url=url, artifact_root=tmp_path / "artifacts")
    ).create_schema()
    (tmp_path / "radar.bit").write_bytes(bitfile())
    monkeypatch.setenv("FPGA_ARTIFACT_ROOT", str(tmp_path / "artifacts"))
    return tmp_path, url


@pytest.fixture()
def run(workspace):
    _, url = workspace
    runner = CliRunner()

    def invoke(*args):
        return runner.invoke(cli, ["--database", url, *args], catch_exceptions=False)

    return invoke


@pytest.fixture()
def digest(run, workspace):
    root, _ = workspace
    return json.loads(run("--json", "image", "upload", str(root / "radar.bit")).output)["digest"]


def export(run, workspace, digest, *extra, name="out.bin"):
    root, _ = workspace
    return run("--json", "image", "export", digest, "--into", str(root / name), *extra)


def test_an_image_comes_out_as_bytes(run, workspace, digest):
    root, _ = workspace
    body = json.loads(export(run, workspace, digest).output)
    written = (root / "out.bin").read_bytes()
    assert body["format"] == "bin"
    assert len(written) == body["bytes"]


def test_the_exported_bytes_are_the_stored_ones(run, workspace, digest):
    root, _ = workspace
    export(run, workspace, digest)
    export(run, workspace, digest, name="again.bin")
    assert (root / "out.bin").read_bytes() == (root / "again.bin").read_bytes()


def test_an_image_can_come_out_as_hex(run, workspace, digest):
    root, _ = workspace
    body = json.loads(export(run, workspace, digest, "--format", "mcs", name="out.mcs").output)
    text = (root / "out.mcs").read_text(encoding="utf-8")
    assert body["format"] == "mcs"
    assert text.startswith(":")
    assert text.rstrip().endswith(":00000001FF")


def test_the_hex_file_reads_back_as_the_same_bytes(run, workspace, digest):
    root, _ = workspace
    export(run, workspace, digest, name="plain.bin")
    export(run, workspace, digest, "--format", "mcs", name="out.mcs")
    start, data = flatten(read_mcs((root / "out.mcs").read_text(encoding="utf-8")))
    assert start == 0
    assert data == (root / "plain.bin").read_bytes()


def test_a_hex_file_carries_the_address_it_loads_at(run, workspace, digest):
    root, _ = workspace
    export(run, workspace, digest, "--format", "mcs", "--at", "0x800000", name="update.mcs")
    start, _ = flatten(read_mcs((root / "update.mcs").read_text(encoding="utf-8")))
    assert start == 0x800000


def test_the_address_can_be_written_in_decimal(run, workspace, digest):
    body = json.loads(export(run, workspace, digest, "--at", "4096").output)
    assert body["address"] == 4096


def test_a_negative_address_is_a_usage_error(run, workspace, digest):
    result = export(run, workspace, digest, "--at", "-1")
    assert result.exit_code != 0


def test_an_unknown_format_is_a_usage_error(run, workspace, digest):
    result = export(run, workspace, digest, "--format", "srec")
    assert result.exit_code != 0
    assert "bin or mcs" in result.output


def test_an_image_can_be_padded_out(run, workspace, digest):
    root, _ = workspace
    body = json.loads(export(run, workspace, digest, "--pad-to", "4096").output)
    assert body["bytes"] == 4096
    assert (root / "out.bin").read_bytes().endswith(b"\xff")


def test_padding_smaller_than_the_image_is_refused(run, workspace, digest):
    assert export(run, workspace, digest, "--pad-to", "4").exit_code != 0


def test_the_spi_flag_bit_reverses_every_byte(run, workspace, digest):
    root, _ = workspace
    export(run, workspace, digest, name="plain.bin")
    export(run, workspace, digest, "--spi", name="spi.bin")
    assert (root / "spi.bin").read_bytes() == reverse_bits((root / "plain.bin").read_bytes())


def test_exporting_an_image_nobody_has_is_an_error(run, workspace):
    assert export(run, workspace, "ab" * 32).exit_code != 0
