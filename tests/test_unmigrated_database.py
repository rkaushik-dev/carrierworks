from datetime import UTC, datetime, timedelta

import pytest
from click.testing import CliRunner
from fastapi.testclient import TestClient

from boardsetup import kintex
from fpga.api.app import build_app
from fpga.api.deps import now as api_now
from fpga.cli.main import cli
from fpga.config import BenchSettings
from fpga.errors import ConfigurationError
from fpga.persistence.engine import unusable_database
from fpga.persistence.unit import BenchDatabase
from fpga.services import inventory

WHEN = datetime(2026, 6, 25, 9, 0, tzinfo=UTC)


@pytest.fixture()
def empty(tmp_path):
    """A database file with nothing in it, which is a site nobody migrated."""
    return f"sqlite+pysqlite:///{tmp_path / 'nothing.db'}"


@pytest.fixture()
def ready(tmp_path):
    """The same thing with its schema built and one board on it."""
    url = f"sqlite+pysqlite:///{tmp_path / 'bench.db'}"
    database = BenchDatabase(BenchSettings(database_url=url))
    database.create_schema()
    with database.unit() as unit:
        inventory.add_board(unit, kintex("bx-14"), WHEN - timedelta(days=1))
    return url, database


def read_boards(url):
    database = BenchDatabase(BenchSettings(database_url=url))
    with database.unit() as unit:
        return unit.boards.all()


def test_a_query_against_no_schema_is_a_bench_error(empty):
    with pytest.raises(ConfigurationError) as raised:
        read_boards(empty)
    assert "no schema yet" in raised.value.message


def test_the_error_says_which_setting_is_wrong(empty):
    with pytest.raises(ConfigurationError) as raised:
        read_boards(empty)
    assert raised.value.details["setting"] == "database_url"


def test_the_error_keeps_the_drivers_own_line(empty):
    with pytest.raises(ConfigurationError) as raised:
        read_boards(empty)
    assert "no such table" in raised.value.details["detail"]


def test_the_error_does_not_carry_the_url(empty):
    with pytest.raises(ConfigurationError) as raised:
        read_boards(empty)
    assert "sqlite" not in str(raised.value.details)


def test_a_migrated_database_answers_normally(ready):
    url, _ = ready
    assert [board.tag for board in read_boards(url)] == ["bx-14"]


def test_the_command_line_prints_one_line_and_exits_one(empty):
    result = CliRunner().invoke(cli, ["--database", empty, "boards", "list"])
    assert result.exit_code == 1
    assert "no schema yet" in result.output
    assert "Traceback" not in result.output


def test_the_api_answers_five_hundred_rather_than_crashing(empty):
    app = build_app(BenchDatabase(BenchSettings(database_url=empty)), BenchSettings())
    app.dependency_overrides[api_now] = lambda: WHEN
    client = TestClient(app, raise_server_exceptions=False)
    response = client.get("/boards")
    assert response.status_code == 500
    assert response.json()["error"]["code"] == "configuration"


def test_readiness_says_it_is_not_ready(empty):
    app = build_app(BenchDatabase(BenchSettings(database_url=empty)), BenchSettings())
    app.dependency_overrides[api_now] = lambda: WHEN
    client = TestClient(app, raise_server_exceptions=False)
    response = client.get("/ready")
    assert response.status_code == 503
    assert response.json()["ready"] is False
    assert "no schema yet" in response.json()["reason"]


def test_a_database_that_cannot_be_opened_at_all_reads_differently(tmp_path):
    from sqlalchemy.exc import OperationalError

    failure = OperationalError("select 1", {}, Exception("unable to open database file"))
    translated = unusable_database(failure)
    assert translated.message == "that database could not be used"
    assert "unable to open" in translated.details["detail"]


def test_the_missing_table_wording_covers_postgres_too():
    from sqlalchemy.exc import OperationalError

    failure = OperationalError("select 1", {}, Exception('relation "boards" does not exist'))
    assert "no schema yet" in unusable_database(failure).message
