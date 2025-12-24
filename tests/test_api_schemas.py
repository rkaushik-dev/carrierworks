from datetime import UTC, datetime, timedelta

import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient
from pydantic import ValidationError as PydanticError

from boardsetup import kintex
from fpga.api.deps import get_settings, now, unit_of_work
from fpga.api.schemas import BoardIn, BoardOut, JobIn, JobOut, LeaseIn, LeaseOut, StateIn
from fpga.config import BenchSettings
from fpga.inventory.state import BoardState
from fpga.jobs.models import ProgramJob
from fpga.leases.models import Lease
from fpga.persistence.unit import BenchDatabase

WHEN = datetime(2025, 12, 24, 9, 0, tzinfo=UTC)


def test_a_board_goes_out_with_what_a_dashboard_needs():
    out = BoardOut.of(kintex("bx-14"), BoardState(state="leased", since=WHEN))
    assert out.asset == "bx-14"
    assert out.device == "xc7k325t"
    assert out.family == "kintex7"
    assert out.location == "rack-2/shelf-04/slot-3"
    assert out.cards == ["fmc-adc-4ch-14b"]
    assert out.transceivers_free == 12
    assert out.state == "leased"


def test_a_board_with_no_state_says_unknown_rather_than_guessing():
    out = BoardOut.of(kintex("bx-14"))
    assert out.state == "unknown"
    assert out.state_since is None


def test_a_board_without_a_cable_or_an_outlet_comes_back_with_nulls():
    out = BoardOut.of(kintex("bx-14", serial=None, outlet=None))
    assert out.adapter is None
    assert out.pdu is None


def test_a_board_arrives_as_the_strings_people_write():
    incoming = BoardIn(
        asset="BX-0014",
        model="rx-carrier-b",
        part="XC7K325T-2FFG900I",
        flash="n25q128a13",
        location="rack-2/shelf-04/slot-3",
        slots=[{"name": "fmc0", "connector": "fmc-hpc", "fitted": "fmc-adc-4ch-14b"}],
    )
    assert incoming.asset == "BX-0014"
    assert incoming.slots[0].fitted == "fmc-adc-4ch-14b"
    assert incoming.vadj_millivolts == 1800


def test_a_board_missing_a_field_is_refused_by_the_schema():
    with pytest.raises(PydanticError):
        BoardIn(asset="bx-14", model="rx-carrier-b")


def test_a_state_change_carries_its_reason():
    change = StateIn(state="faulted", reason="chain read as all ones", actor="nightly")
    assert change.reason == "chain read as all ones"
    assert StateIn(state="available").reason == ""


def test_a_lease_goes_out_whole():
    lease = Lease(
        id="lease-00001",
        board="bx-14",
        holder="team-a",
        purpose="capture",
        taken_at=WHEN,
        expires_at=WHEN + timedelta(hours=1),
    )
    out = LeaseOut.of(lease)
    assert out.id == "lease-00001"
    assert out.expires_at == WHEN + timedelta(hours=1)
    assert out.released_at is None


def test_a_released_lease_carries_its_reason():
    lease = Lease(
        id="lease-00001",
        board="bx-14",
        holder="team-a",
        purpose="capture",
        taken_at=WHEN,
        expires_at=WHEN + timedelta(hours=1),
    ).release(WHEN + timedelta(minutes=10), "finished")
    out = LeaseOut.of(lease)
    assert out.released_at is not None
    assert out.release_reason == "finished"


def test_a_lease_request_defaults_to_the_middle_priority():
    asked = LeaseIn(holder="team-a", purpose="capture")
    assert asked.priority == 5
    assert asked.minutes is None
    assert asked.preemptible


def test_a_job_goes_out_with_its_error():
    job = ProgramJob(
        id="job-00001",
        board="bx-14",
        digest="ab" * 32,
        mode="jtag",
        requested_by="team-a",
        created_at=WHEN,
    )
    failed = job.start(WHEN).failed(WHEN + timedelta(minutes=1), "cable", code="chain")
    out = JobOut.of(failed)
    assert out.state == "failed"
    assert out.error_code == "chain"
    assert out.attempts == 1


def test_a_job_request_defaults_to_a_verified_jtag_load():
    asked = JobIn(board="bx-14", digest="ab" * 32, requested_by="team-a")
    assert asked.mode == "jtag"
    assert asked.verify


def test_the_clock_is_a_dependency_a_test_can_replace():
    app = FastAPI()
    app.state.database = BenchDatabase.in_memory()
    app.state.settings = BenchSettings()

    @app.get("/when")
    def when(moment=Depends(now)):
        return {"now": moment.isoformat()}

    app.dependency_overrides[now] = lambda: WHEN
    assert TestClient(app).get("/when").json()["now"] == WHEN.isoformat()


def test_a_unit_of_work_is_handed_to_a_route_and_committed():
    app = FastAPI()
    app.state.database = BenchDatabase.in_memory()
    app.state.settings = BenchSettings()

    @app.post("/boards")
    def add(unit=Depends(unit_of_work)):
        unit.boards.add(kintex("bx-14"), BoardState(state="available", since=WHEN))
        return {"added": True}

    @app.get("/boards")
    def listing(unit=Depends(unit_of_work)):
        return {"boards": [board.tag for board in unit.boards.all()]}

    client = TestClient(app)
    assert client.post("/boards").json() == {"added": True}
    assert client.get("/boards").json() == {"boards": ["bx-14"]}


def test_the_settings_dependency_hands_back_what_the_app_holds():
    app = FastAPI()
    app.state.settings = BenchSettings(site="lab-c")
    app.state.database = BenchDatabase.in_memory()

    @app.get("/site")
    def site(settings=Depends(get_settings)):
        return {"site": settings.site}

    assert TestClient(app).get("/site").json() == {"site": "lab-c"}
