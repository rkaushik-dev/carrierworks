"""The report routes: reading a build's numbers in and asking what they mean."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from fastapi import APIRouter, Body, Depends

from fpga.access import ApiKey
from fpga.api.deps import now, unit_of_work
from fpga.api.security import needs
from fpga.errors import NotFound
from fpga.persistence.unit import UnitOfWork
from fpga.reports.baselines import BuildReport
from fpga.services import reports

router = APIRouter(tags=["reports"])


def _out(report: BuildReport) -> dict[str, Any]:
    return {
        "digest": report.digest,
        "design": report.design,
        "closed": report.closed,
        "wns_ps": report.timing.wns_ps,
        "whs_ps": report.timing.whs_ps,
        "worst_slack_ps": report.timing.worst_slack_ps,
        "failing_endpoints": report.timing.failing_endpoints,
        "luts": report.utilization.luts,
        "registers": report.utilization.registers,
        "block_ram": report.utilization.block_ram,
        "dsp": report.utilization.dsp,
    }


@router.post("/artifacts/{digest}/reports", status_code=201)
def ingest_reports(
    digest: str,
    timing: str = Body(embed=True),
    utilization: str = Body(embed=True),
    unit: UnitOfWork = Depends(unit_of_work),
    moment: datetime = Depends(now),
    _: ApiKey = Depends(needs("operator")),
) -> dict[str, Any]:
    """Read a build's timing and utilization reports against its image."""
    report = reports.ingest(
        unit,
        digest,
        moment,
        timing_text=timing,
        utilization_text=utilization,
    )
    return _out(report)


@router.get("/artifacts/{digest}/reports")
def read_reports(
    digest: str,
    unit: UnitOfWork = Depends(unit_of_work),
    _: ApiKey = Depends(needs("reader")),
) -> dict[str, Any]:
    """What the build said about one image."""
    report = unit.reports.for_digest(digest)
    if report is None:
        raise NotFound("build report", digest)
    return _out(report)


@router.get("/artifacts/{digest}/regressions")
def read_regressions(
    digest: str,
    unit: UnitOfWork = Depends(unit_of_work),
    _: ApiKey = Depends(needs("reader")),
) -> dict[str, Any]:
    """How this build compares with the one its design is measured against."""
    found = reports.regressions(unit, digest)
    return {"digest": digest, "regressions": list(found), "clean": not found}


@router.get("/designs/{design}/baseline")
def read_baseline(
    design: str,
    unit: UnitOfWork = Depends(unit_of_work),
    _: ApiKey = Depends(needs("reader")),
) -> dict[str, Any] | None:
    """The build a design is measured against, if one has been pinned."""
    found = reports.baseline(unit, design)
    return None if found is None else _out(found)


@router.post("/designs/{design}/baseline")
def pin_baseline(
    design: str,
    digest: str = Body(embed=True),
    unit: UnitOfWork = Depends(unit_of_work),
    _: ApiKey = Depends(needs("admin")),
) -> dict[str, Any]:
    """Pin a design's baseline to a build somebody was happy with."""
    report = reports.set_baseline(unit, digest)
    if report.design != design.strip().lower():
        raise NotFound("build report", digest)
    return _out(report)
