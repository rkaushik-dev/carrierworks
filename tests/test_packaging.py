"""What a test suite structurally cannot catch, checked by reading the metadata.

A dev dependency imported by a shipped module works perfectly in every test
run, because a test run is the one environment where the dev extra is always
installed. It fails the moment somebody installs the package on its own, and it
fails as an import error at startup rather than anywhere useful.

So this walks the syntax tree of every module under src, collects the third
party imports, and checks each one against the declared runtime dependencies.
It also checks that every declared dependency is pinned, because a bench that
resolves a different sqlalchemy on the day somebody rebuilds the container is a
bench with a new set of bugs.
"""

import ast
import sys
import tomllib
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src" / "fpga"

# Distributions whose import name is not their package name.
_IMPORT_NAMES = {
    "sqlalchemy": "SQLAlchemy",
    "alembic": "alembic",
}


def _metadata():
    return tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))


def _requirement_names(entries):
    names = set()
    for entry in entries:
        for separator in ("==", ">=", "<=", "~=", "["):
            if separator in entry:
                entry = entry.split(separator)[0]
        names.add(entry.strip().lower())
    return names


def _imports_of(path):
    tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
    found = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            found.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.level == 0 and node.module:
            found.add(node.module.split(".")[0])
    return found


def _third_party(names):
    return {
        name
        for name in names
        if name != "fpga" and name not in sys.stdlib_module_names and not name.startswith("_")
    }


def test_every_shipped_module_only_imports_declared_dependencies():
    declared = _requirement_names(_metadata()["project"]["dependencies"])
    for path in sorted(SRC.rglob("*.py")):
        for name in _third_party(_imports_of(path)):
            distribution = _IMPORT_NAMES.get(name, name).lower()
            assert distribution in declared, f"{path.name} imports {name}"


def test_no_shipped_module_imports_a_dev_only_dependency():
    dev = _requirement_names(_metadata()["project"]["optional-dependencies"]["dev"])
    runtime = _requirement_names(_metadata()["project"]["dependencies"])
    dev_only = dev - runtime
    for path in sorted(SRC.rglob("*.py")):
        for name in _third_party(_imports_of(path)):
            assert _IMPORT_NAMES.get(name, name).lower() not in dev_only, path.name


def test_every_dependency_is_pinned():
    project = _metadata()["project"]
    entries = list(project["dependencies"])
    for extra in project.get("optional-dependencies", {}).values():
        entries.extend(extra)
    for entry in entries:
        assert "==" in entry, entry


def test_the_package_declares_the_python_it_needs():
    assert _metadata()["project"]["requires-python"] == ">=3.11"


def test_the_tests_are_not_shipped():
    packages = _metadata()["tool"]["setuptools"]["packages"]["find"]
    assert packages["where"] == ["src"]
    assert not (SRC / "tests").exists()


@pytest.mark.parametrize("name", ["pytest", "ruff", "mypy"])
def test_the_tooling_is_in_the_dev_extra(name):
    dev = _requirement_names(_metadata()["project"]["optional-dependencies"]["dev"])
    assert name in dev


def test_sqlalchemy_is_only_imported_from_the_persistence_package():
    """Everything above persistence works on plain objects, and this proves it."""
    for path in sorted(SRC.rglob("*.py")):
        if "persistence" in path.parts:
            continue
        assert "sqlalchemy" not in _imports_of(path), path.name
