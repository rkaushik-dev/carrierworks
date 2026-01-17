from datetime import UTC, datetime, timedelta

import pytest

from fpga.access import ROLES, ApiKey, hash_secret, require_role
from fpga.errors import Forbidden, NotFound, Unauthorized, ValidationError
from fpga.persistence.unit import BenchDatabase
from fpga.services import security

NOW = datetime(2026, 1, 24, 9, 0, tzinfo=UTC)


@pytest.fixture()
def database():
    return BenchDatabase.in_memory()


def test_the_roles_run_from_reading_to_changing_things():
    assert ROLES == ("reader", "operator", "admin")


def test_a_role_covers_everything_below_it():
    admin = ApiKey(id="1", label="ops", role="admin")
    assert admin.allows("reader")
    assert admin.allows("operator")
    assert admin.allows("admin")


def test_a_reader_cannot_do_an_operator_s_work():
    reader = ApiKey(id="1", label="dashboard", role="reader")
    assert reader.allows("reader")
    assert not reader.allows("operator")


def test_a_revoked_key_allows_nothing():
    dead = ApiKey(id="1", label="old", role="admin", active=False)
    assert not dead.allows("reader")


def test_a_role_nobody_has_is_a_bug():
    with pytest.raises(ValidationError):
        ApiKey(id="1", label="x", role="wizard")
    with pytest.raises(ValidationError):
        ApiKey(id="1", label="x", role="reader").allows("wizard")


def test_a_key_without_a_label_is_refused():
    with pytest.raises(ValidationError):
        ApiKey(id="1", label="  ", role="reader")


def test_requiring_a_role_says_what_was_needed():
    reader = ApiKey(id="1", label="dashboard", role="reader")
    with pytest.raises(Forbidden) as caught:
        require_role(reader, "admin")
    assert caught.value.details == {"needs": "admin", "holds": "reader"}
    assert require_role(reader, "reader") is reader


def test_a_secret_hashes_to_a_digest():
    assert len(hash_secret("hunter2")) == 64
    assert hash_secret("hunter2") == hash_secret(" hunter2 ")
    assert hash_secret("hunter2") != hash_secret("hunter3")


def test_an_empty_secret_is_refused():
    with pytest.raises(ValidationError):
        hash_secret("   ")


def test_issuing_a_key_hands_the_secret_back_once(database):
    with database.unit() as unit:
        key, secret = security.issue(unit, "nightly runner", "operator", NOW)
    assert key.role == "operator"
    assert len(secret) > 20
    with database.unit() as unit:
        stored = security.keys(unit)
    assert [entry.label for entry in stored] == ["nightly runner"]


def test_the_secret_is_not_stored_anywhere(database):
    with database.unit() as unit:
        _, secret = security.issue(unit, "nightly runner", "operator", NOW)
    with database.unit() as unit:
        rows = security.keys(unit)
    assert secret not in str(rows)


def test_a_key_authenticates_with_its_secret(database):
    with database.unit() as unit:
        issued, secret = security.issue(unit, "nightly runner", "operator", NOW)
    with database.unit() as unit:
        found = security.authenticate(unit, secret, NOW)
    assert found.id == issued.id
    assert found.role == "operator"


def test_a_secret_nobody_issued_is_refused(database):
    with database.unit() as unit:
        security.issue(unit, "nightly runner", "operator", NOW)
    with database.unit() as unit, pytest.raises(Unauthorized):
        security.authenticate(unit, "not-a-key", NOW)


def test_no_key_at_all_is_refused(database):
    with database.unit() as unit, pytest.raises(Unauthorized):
        security.authenticate(unit, None, NOW)
    with database.unit() as unit, pytest.raises(Unauthorized):
        security.authenticate(unit, "   ", NOW)


def test_a_revoked_key_is_refused_with_a_different_error(database):
    with database.unit() as unit:
        issued, secret = security.issue(unit, "old laptop", "admin", NOW)
    with database.unit() as unit:
        security.revoke(unit, issued.id)
    with database.unit() as unit, pytest.raises(Forbidden):
        security.authenticate(unit, secret, NOW)


def test_revoking_a_key_keeps_the_record_of_it(database):
    with database.unit() as unit:
        issued, _ = security.issue(unit, "old laptop", "admin", NOW)
    with database.unit() as unit:
        security.revoke(unit, issued.id)
    with database.unit() as unit:
        held = security.keys(unit)
    assert [entry.active for entry in held] == [False]
    assert held[0].describe() == "old laptop [admin] (revoked)"


def test_revoking_a_key_nobody_issued_is_a_not_found(database):
    with database.unit() as unit, pytest.raises(NotFound):
        security.revoke(unit, "nothing")


def test_using_a_key_records_when(database):
    with database.unit() as unit:
        issued, secret = security.issue(unit, "nightly runner", "operator", NOW)
    later = NOW + timedelta(hours=2)
    with database.unit() as unit:
        security.authenticate(unit, secret, later)
    with database.unit() as unit:
        row = unit.api_keys.get(issued.id)
    assert row.active


def test_two_keys_can_share_a_role_but_not_a_secret(database):
    with database.unit() as unit:
        security.issue(unit, "runner one", "operator", NOW, secret="alpha")
        security.issue(unit, "runner two", "operator", NOW, secret="beta")
    with database.unit() as unit:
        assert len(security.keys(unit)) == 2
        assert security.authenticate(unit, "beta", NOW).label == "runner two"
