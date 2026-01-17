from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory
from sqlalchemy import create_engine, inspect, text

from fpga.persistence.models import Base

ROOT = Path(__file__).resolve().parents[1]


def alembic_config(url):
    config = Config(str(ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(ROOT / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    return config


@pytest.fixture()
def url(tmp_path):
    return f"sqlite+pysqlite:///{tmp_path / 'migrated.db'}"


def test_the_baseline_builds_every_table(url):
    command.upgrade(alembic_config(url), "head")
    inspector = inspect(create_engine(url))
    assert set(Base.metadata.tables) <= set(inspector.get_table_names())


def test_the_migration_and_the_models_agree_on_columns(url):
    command.upgrade(alembic_config(url), "head")
    inspector = inspect(create_engine(url))
    for name, table in Base.metadata.tables.items():
        migrated = {column["name"] for column in inspector.get_columns(name)}
        assert {column.name for column in table.columns} == migrated, name


def test_the_indexes_the_queries_depend_on_are_there(url):
    command.upgrade(alembic_config(url), "head")
    inspector = inspect(create_engine(url))
    lease_indexes = {index["name"] for index in inspector.get_indexes("leases")}
    assert "uq_leases_active_board" in lease_indexes
    job_indexes = {index["name"] for index in inspector.get_indexes("jobs")}
    assert "ix_jobs_board_state" in job_indexes


def test_the_one_open_lease_index_is_unique(url):
    command.upgrade(alembic_config(url), "head")
    inspector = inspect(create_engine(url))
    indexes = {index["name"]: index for index in inspector.get_indexes("leases")}
    assert indexes["uq_leases_active_board"]["unique"]


def test_the_downgrade_leaves_nothing_behind(url):
    config = alembic_config(url)
    command.upgrade(config, "head")
    command.downgrade(config, "base")
    inspector = inspect(create_engine(url))
    remaining = set(inspector.get_table_names()) - {"alembic_version"}
    assert remaining == set()


def test_the_migration_runs_twice_without_complaint(url):
    config = alembic_config(url)
    command.upgrade(config, "head")
    command.upgrade(config, "head")
    inspector = inspect(create_engine(url))
    assert "boards" in inspector.get_table_names()


def test_the_version_is_stamped_at_the_latest_revision(url):
    """The stamp is the head revision, whichever that is today."""
    config = alembic_config(url)
    command.upgrade(config, "head")
    engine = create_engine(url)
    with engine.connect() as connection:
        found = connection.execute(text("select version_num from alembic_version")).scalar_one()
    latest = ScriptDirectory.from_config(config).get_current_head()
    assert found == latest


def test_the_baseline_can_be_stopped_at_and_then_carried_on(url):
    """A site part way up the history upgrades the rest of the way."""
    config = alembic_config(url)
    command.upgrade(config, "0001")
    inspector = inspect(create_engine(url))
    assert "api_keys" not in inspector.get_table_names()
    command.upgrade(config, "head")
    assert "api_keys" in inspect(create_engine(url)).get_table_names()
