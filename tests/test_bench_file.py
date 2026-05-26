import json
from datetime import UTC, datetime, timedelta

import pytest
from click.testing import CliRunner

from boardsetup import kintex
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.errors import ConfigurationError, NotFound
from fpga.persistence.unit import BenchDatabase
from fpga.services import bench_file, inventory

WHEN = datetime(2026, 6, 1, 9, 0, tzinfo=UTC)

BENCH = {
    "site": "lab-a",
    "boards": [
        {
            "asset": "bx-20",
            "model": "rx-carrier-b",
            "part": "XC7K325T-2FFG900I",
            "flash": "n25q128a13",
            "location": "rack-3/shelf-02/slot-1",
            "adapter": {"serial": "210308EE0001", "model": "jtag-hs2"},
            "pdu": "pdu-c/outlet-03",
            "slots": [{"name": "fmc0", "connector": "fmc-hpc", "fitted": "fmc-adc-4ch-14b"}],
        },
        {
            "asset": "bx-21",
            "model": "tx-carrier-a",
            "part": "XC7A100T-2FGG484I",
            "flash": "w25q64jv",
            "location": "rack-3/shelf-02/slot-2",
        },
    ],
}


def text_of(payload=None):
    return json.dumps(payload or BENCH)


@pytest.fixture()
def database(tmp_path):
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    built = BenchDatabase(BenchSettings(database_url=url, site="lab-a"))
    built.create_schema()
    return built, url


@pytest.fixture()
def run(database):
    _, url = database
    runner = CliRunner()

    def invoke(*args):
        return runner.invoke(cli, ["--database", url, *args], catch_exceptions=False)

    return invoke


def load(database, payload=None, **rest):
    built, _ = database
    with built.unit() as unit:
        return bench_file.import_bench(unit, text_of(payload), WHEN, **rest)


def test_a_file_puts_its_boards_on_the_bench(database):
    outcome = load(database)
    assert outcome.added == ("bx-20", "bx-21")
    built, _ = database
    with built.unit() as unit:
        assert {board.tag for board in unit.boards.all()} == {"bx-20", "bx-21"}


def test_the_cards_come_across(database):
    load(database)
    built, _ = database
    with built.unit() as unit:
        board = unit.boards.get("bx-20")
    assert [spec.name for spec in board.fitted_cards()] == ["fmc-adc-4ch-14b"]


def test_running_the_same_file_twice_changes_nothing(database):
    load(database)
    outcome = load(database)
    assert outcome.added == ()
    assert outcome.skipped == ("bx-20", "bx-21")


def test_replacing_is_asked_for_explicitly(database):
    load(database)
    moved = json.loads(json.dumps(BENCH))
    moved["boards"][0]["location"] = "rack-4/shelf-01/slot-1"
    outcome = load(database, moved, replace=True)
    assert outcome.replaced == ("bx-20", "bx-21")
    built, _ = database
    with built.unit() as unit:
        assert str(unit.boards.get("bx-20").location) == "rack-4/shelf-01/slot-1"


def test_a_file_that_is_not_json_is_refused(database):
    built, _ = database
    with built.unit() as unit, pytest.raises(ConfigurationError):
        bench_file.import_bench(unit, "{not json", WHEN)


def test_a_board_missing_a_field_is_refused(database):
    broken = {"site": "lab-a", "boards": [{"asset": "bx-22"}]}
    with pytest.raises(ConfigurationError):
        load(database, broken)


def test_the_outcome_reads_as_one_line(database):
    assert "added bx-20, bx-21" in load(database).describe()


def test_an_empty_file_says_so(database):
    assert load(database, {"site": "lab-a", "boards": []}).describe() == "nothing in that file"


def test_the_export_carries_every_board(database):
    load(database)
    built, _ = database
    with built.unit() as unit:
        payload = bench_file.export_bench(unit)
    assert [entry["asset"] for entry in payload["boards"]] == ["bx-20", "bx-21"]


def test_the_export_carries_the_state(database):
    load(database)
    built, _ = database
    with built.unit() as unit:
        inventory.move_state(unit, "bx-20", "maintenance", WHEN, reason="fan", actor="sam")
        payload = bench_file.export_bench(unit)
    states = {entry["asset"]: entry["state"] for entry in payload["boards"]}
    assert states["bx-20"] == "maintenance"


def test_an_export_reads_back_as_the_same_bench(database, tmp_path):
    load(database)
    built, _ = database
    with built.unit() as unit:
        payload = bench_file.export_bench(unit)
    other = BenchDatabase(BenchSettings(database_url=f"sqlite+pysqlite:///{tmp_path / 'other.db'}"))
    other.create_schema()
    with other.unit() as unit:
        outcome = bench_file.import_bench(unit, json.dumps(payload, default=str), WHEN)
        assert outcome.added == ("bx-20", "bx-21")
        assert unit.boards.get("bx-20").flash == "n25q128a13"


def test_reading_a_file_that_is_not_there_is_an_error(tmp_path):
    with pytest.raises(NotFound):
        bench_file.read_file(tmp_path / "nothing.json")


def test_the_command_imports_a_file(run, tmp_path):
    path = tmp_path / "bench.json"
    path.write_text(text_of(), encoding="utf-8")
    body = json.loads(run("--json", "boards", "import", str(path)).output)
    assert body["added"] == ["bx-20", "bx-21"]


def test_the_command_leaves_known_boards_alone(run, tmp_path, database):
    built, _ = database
    with built.unit() as unit:
        inventory.add_board(unit, kintex("bx-20"), WHEN - timedelta(days=1))
    path = tmp_path / "bench.json"
    path.write_text(text_of(), encoding="utf-8")
    body = json.loads(run("--json", "boards", "import", str(path)).output)
    assert body["skipped"] == ["bx-20"]


def test_the_command_can_replace(run, tmp_path, database):
    built, _ = database
    with built.unit() as unit:
        inventory.add_board(unit, kintex("bx-20"), WHEN - timedelta(days=1))
    path = tmp_path / "bench.json"
    path.write_text(text_of(), encoding="utf-8")
    body = json.loads(run("--json", "boards", "import", str(path), "--replace").output)
    assert body["replaced"] == ["bx-20"]


def test_importing_a_file_that_is_not_there_is_an_error(run, tmp_path):
    assert run("boards", "import", str(tmp_path / "nothing.json")).exit_code != 0


def test_the_command_exports_to_the_terminal(run, tmp_path):
    path = tmp_path / "bench.json"
    path.write_text(text_of(), encoding="utf-8")
    run("boards", "import", str(path))
    payload = json.loads(run("boards", "export").output)
    assert payload["site"] == "lab-a"
    assert len(payload["boards"]) == 2


def test_the_command_exports_to_a_file(run, tmp_path):
    path = tmp_path / "bench.json"
    path.write_text(text_of(), encoding="utf-8")
    run("boards", "import", str(path))
    out = tmp_path / "out.json"
    result = run("boards", "export", "--into", str(out))
    assert "2 boards written" in result.output
    assert json.loads(out.read_text(encoding="utf-8"))["boards"][0]["asset"] == "bx-20"
