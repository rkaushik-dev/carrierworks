"""The queue routes: ask for a board, see the line, run a pass over it.

The interesting one is the get on a single request, because that is what a
build job polls while it waits. It answers with the place in the line and the
board once there is one, so a job can block on a board without knowing
anything about how the bench decides.

Dispatch is a post and it is behind the operator role. Everything a pass does
is a lease somebody else could have taken by hand, so it is not privileged
work, but it is work that changes who has what.
"""

from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Body, Depends, Query

from fpga.access import ApiKey
from fpga.api.deps import get_settings, now, unit_of_work
from fpga.api.schemas import DispatchOut, RequestIn, RequestOut
from fpga.api.security import needs
from fpga.config import BenchSettings
from fpga.persistence.unit import UnitOfWork
from fpga.services import scheduling as queue_service

router = APIRouter(tags=["queue"])


def _place(unit: UnitOfWork, request_id: str, moment: datetime) -> int | None:
    """Where a request sits in the line, or nothing once it is closed."""
    waiting = unit.requests.ordered(moment)
    for place, entry in enumerate(waiting, start=1):
        if entry.id == request_id:
            return place
    return None


@router.get("/requests", response_model=list[RequestOut])
def list_requests(
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    holder: str | None = Query(default=None, description="one team rather than all of them"),
    closed: bool = Query(default=False, description="include served and cancelled requests"),
    _: ApiKey = Depends(needs("reader")),
) -> list[RequestOut]:
    """The line, in the order it will be served."""
    if holder is not None:
        found = unit.requests.for_holder(holder)
    elif closed:
        found = unit.requests.all()
    else:
        found = unit.requests.ordered(moment)
    return [
        RequestOut.of(entry, moment, board=unit.requests.board_for(entry.id)) for entry in found
    ]


@router.post("/requests", response_model=RequestOut, status_code=201)
def submit_request(
    asked: RequestIn,
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    _: ApiKey = Depends(needs("operator")),
) -> RequestOut:
    """Ask for a board and take a place in the line."""
    request = queue_service.submit(
        unit,
        asked.holder,
        asked.purpose,
        asked.needs,
        moment,
        priority=asked.priority,
        minutes=asked.minutes,
        preemptible=asked.preemptible,
        needs_hands=asked.needs_hands,
    )
    return RequestOut.of(request, moment, place=_place(unit, request.id, moment))


@router.get("/requests/{request_id}", response_model=RequestOut)
def read_request(
    request_id: str,
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    _: ApiKey = Depends(needs("reader")),
) -> RequestOut:
    """One request, with its place in the line and its board once it has one."""
    request = unit.requests.get(request_id)
    return RequestOut.of(
        request,
        moment,
        board=unit.requests.board_for(request_id),
        place=_place(unit, request_id, moment),
    )


@router.delete("/requests/{request_id}", response_model=RequestOut)
def cancel_request(
    request_id: str,
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    _: ApiKey = Depends(needs("operator")),
) -> RequestOut:
    """Give up a place in the line."""
    return RequestOut.of(queue_service.cancel(unit, request_id, moment), moment)


@router.post("/requests/dispatch", response_model=DispatchOut)
def dispatch(
    quotas: dict[str, int] = Body(default_factory=dict),
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    settings: BenchSettings = Depends(get_settings),
    limit: int = Query(default=0, ge=0, le=64, description="hand out at most this many"),
    _: ApiKey = Depends(needs("operator")),
) -> DispatchOut:
    """One pass over the line, taking every board it can."""
    outcome = queue_service.dispatch(
        unit,
        moment,
        settings=settings,
        quotas=quotas,
        limit=limit,
    )
    return DispatchOut.of(outcome)
