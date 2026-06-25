import os
import subprocess
import sys
import tomllib
from pathlib import Path

import fpga
from fpga.cli.main import cli

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "fpga"


def project():
    return tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))


def in_checkout():
    """An environment where the package is importable from the checkout.

    The tests run against the sources rather than an installed wheel, so a
    subprocess needs the same path the test session gave itself. Anything
    else would be testing the machine this happens to run on.
    """
    env = dict(os.environ)
    env["PYTHONPATH"] = str(ROOT / "src")
    return env


def run_module(*args, cwd=None):
    return subprocess.run(
        [sys.executable, "-m", "fpga", *args],
        capture_output=True,
        text=True,
        check=False,
        cwd=cwd or ROOT,
        env=in_checkout(),
    )


def test_the_package_says_its_types_are_meant_to_be_used():
    assert (SRC / "py.typed").is_file()


def test_the_marker_is_packaged():
    data = project()["tool"]["setuptools"]["package-data"]
    assert data["fpga"] == ["py.typed"]


def test_the_version_is_read_off_the_package():
    assert project()["tool"]["setuptools"]["dynamic"]["version"] == {"attr": "fpga.__version__"}
    assert "version" in project()["project"]["dynamic"]


def test_the_package_carries_a_version():
    assert fpga.__version__.count(".") == 2


def test_the_module_runs_as_a_program():
    finished = run_module("--help")
    assert finished.returncode == 0
    assert "boards" in finished.stdout


def test_the_module_and_the_console_script_are_the_same_command():
    assert project()["project"]["scripts"]["fpga"] == "fpga.cli.main:run"
    finished = run_module("version")
    assert finished.returncode == 0
    assert fpga.__version__ in finished.stdout


def test_every_command_group_is_reachable_from_the_module():
    finished = run_module("--help")
    for group in ("boards", "chain", "flash", "image", "job", "lease", "power", "queue"):
        assert group in finished.stdout, group


def test_the_groups_registered_in_process_match_the_ones_advertised():
    from fpga.cli.main import run  # noqa: F401  (imports every command module)

    finished = run_module("--help")
    for name in cli.commands:
        assert name in finished.stdout, name


def test_a_command_that_needs_a_database_fails_cleanly_not_with_a_traceback(tmp_path):
    finished = run_module(
        "--database",
        "sqlite+pysqlite:///nothing.db",
        "boards",
        "list",
        cwd=tmp_path,
    )
    assert finished.returncode == 1
    assert "Traceback" not in finished.stderr
    assert "no schema yet" in finished.stderr
