import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

import check  # noqa: E402  (the script lives outside the package)


def test_the_gates_run_fast_ones_first():
    assert [gate.name for gate in check.GATES] == ["lint", "format", "types", "tests"]


def test_every_gate_runs_through_this_interpreter():
    for gate in check.GATES:
        assert gate.argv()[:2] == [sys.executable, "-m"]


def test_no_gates_asked_for_means_all_of_them():
    assert check.select([]) == check.GATES


def test_one_gate_can_be_picked_out():
    assert [gate.name for gate in check.select(["types"])] == ["types"]


def test_gates_come_back_in_declared_order_not_the_order_asked_for():
    picked = check.select(["tests", "lint"])
    assert [gate.name for gate in picked] == ["lint", "tests"]


def test_a_gate_nobody_has_is_refused():
    with pytest.raises(SystemExit):
        check.select(["vibes"])


def test_a_failing_gate_stops_the_run(monkeypatch):
    seen = []

    def fake_run(gate):
        seen.append(gate.name)
        return (1 if gate.name == "lint" else 0), 0.0

    monkeypatch.setattr(check, "run", fake_run)
    assert check.main([]) == 1
    assert seen == ["lint"]


def test_keep_going_runs_the_rest(monkeypatch):
    seen = []

    def fake_run(gate):
        seen.append(gate.name)
        return (1 if gate.name == "lint" else 0), 0.0

    monkeypatch.setattr(check, "run", fake_run)
    assert check.main(["--keep-going"]) == 1
    assert seen == ["lint", "format", "types", "tests"]


def test_a_clean_run_is_zero(monkeypatch):
    monkeypatch.setattr(check, "run", lambda gate: (0, 0.0))
    assert check.main([]) == 0


def test_the_script_runs_as_a_program():
    finished = subprocess.run(
        [sys.executable, str(ROOT / "scripts" / "check.py"), "--help"],
        capture_output=True,
        text=True,
        check=False,
    )
    assert finished.returncode == 0
    assert "run the bench checks" in finished.stdout
