"""Every gate the bench has to pass, in one command, in the same order as CI.

Written because the checks kept being run in different orders by different
people and by the workflow, and the order matters: a formatting change that
lands after the type check has to be reviewed again, and a test suite that runs
before the lint spends two minutes proving something an unused import would
have failed in two seconds.

Fast gates first, then types, then the suite. Anything that fails stops the
rest unless --keep-going says otherwise, which is what somebody wants locally
and never what CI wants.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


@dataclass(frozen=True, slots=True)
class Gate:
    """One check, and what to run for it."""

    name: str
    command: tuple[str, ...]

    def argv(self) -> list[str]:
        return [sys.executable, "-m", *self.command]


GATES: tuple[Gate, ...] = (
    Gate("lint", ("ruff", "check", ".")),
    Gate("format", ("ruff", "format", "--check", ".")),
    Gate("types", ("mypy",)),
    Gate("tests", ("pytest", "-q")),
)


def run(gate: Gate) -> tuple[int, float]:
    """Run one gate from the repository root, and time it."""
    started = time.monotonic()
    finished = subprocess.run(gate.argv(), cwd=ROOT, check=False)  # noqa: S603
    return finished.returncode, time.monotonic() - started


def select(names: Sequence[str]) -> tuple[Gate, ...]:
    """The gates asked for, in the order they are declared in."""
    if not names:
        return GATES
    known = {gate.name: gate for gate in GATES}
    unknown = [name for name in names if name not in known]
    if unknown:
        raise SystemExit(f"no such gate: {', '.join(unknown)}")
    return tuple(gate for gate in GATES if gate.name in set(names))


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="run the bench checks")
    parser.add_argument("gates", nargs="*", help="which gates to run, default all of them")
    parser.add_argument(
        "--keep-going",
        action="store_true",
        help="run every gate even after one fails",
    )
    parsed = parser.parse_args(argv)

    failures: list[str] = []
    for gate in select(parsed.gates):
        code, seconds = run(gate)
        status = "ok" if code == 0 else f"failed ({code})"
        print(f"{gate.name:<7} {status} in {seconds:.1f}s", flush=True)
        if code != 0:
            failures.append(gate.name)
            if not parsed.keep_going:
                break
    if failures:
        print("failed: " + ", ".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
