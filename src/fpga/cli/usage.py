"""The usage report, which is what gets printed before a budget meeting.

Three views over the same window, because the three questions are different.
Boards answers whether the hardware is being used. Teams answers who is using
it. Idle answers which boards to move, lend out or stop paying maintenance on.
"""

from __future__ import annotations

from datetime import timedelta

import click

from fpga.cli.main import Context, bench_command, cli
from fpga.services import usage as usage_service

__all__ = ["usage_group"]


@cli.group("usage")
def usage_group() -> None:
    """Who used what, and for how long."""


def _report(context: Context, days: int) -> usage_service.UsageReport:
    with context.unit() as unit:
        return usage_service.usage(unit, context.now, window=timedelta(days=days))


@usage_group.command("boards")
@click.option("--days", default=7, help="how far back to look")
@click.pass_obj
@bench_command
def boards(context: Context, days: int) -> None:
    """How much of the window each board spent leased."""
    report = _report(context, days)
    rows = [
        {
            "asset": board,
            "used": f"{fraction * 100:.1f}%",
            "hours": round(fraction * report.hours, 1),
        }
        for board, fraction in sorted(
            report.utilisation.items(),
            key=lambda item: (-item[1], item[0]),
        )
    ]
    context.emit(rows, ("asset", "used", "hours"))


@usage_group.command("teams")
@click.option("--days", default=7, help="how far back to look")
@click.pass_obj
@bench_command
def teams(context: Context, days: int) -> None:
    """Board hours by team, most first."""
    report = _report(context, days)
    rows = [
        {"holder": holder, "hours": hours}
        for holder, hours in sorted(
            report.holder_hours.items(),
            key=lambda item: (-item[1], item[0]),
        )
    ]
    context.emit(rows, ("holder", "hours"))


@usage_group.command("idle")
@click.option("--days", default=30, help="how far back to look")
@click.pass_obj
@bench_command
def idle(context: Context, days: int) -> None:
    """Boards nobody leased at all in the window."""
    report = _report(context, days)
    context.say(
        f"{len(report.idle)} of {len(report.boards)} boards idle for {days} days: "
        + (", ".join(report.idle) or "none"),
        {"days": days, "idle": list(report.idle), "boards": list(report.boards)},
    )


@usage_group.command("summary")
@click.option("--days", default=7, help="how far back to look")
@click.pass_obj
@bench_command
def summary(context: Context, days: int) -> None:
    """One line, and the numbers behind it as json."""
    report = _report(context, days)
    context.say(
        report.describe(),
        {
            "start": report.start,
            "end": report.end,
            "hours": report.hours,
            "leases": report.leases,
            "waiting": report.waiting,
            "busiest": report.busiest,
            "utilisation": report.utilisation,
            "holder_hours": report.holder_hours,
            "idle": list(report.idle),
        },
    )
