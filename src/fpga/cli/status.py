"""The one command somebody runs before they ask anybody anything.

It prints the roll up in a shape that fits on a terminal that is already full
of something else, and the same numbers as json for whatever is drawing the
wall display in the lab.

The exit code carries the health, so this is also what a check script calls:
zero when nothing needs a person, and two when something does.
"""

from __future__ import annotations

import click

from fpga.cli.main import Context, bench_command, cli
from fpga.services import overview as overview_service

__all__ = ["status"]


@cli.command("status")
@click.option("--fail-on-trouble", is_flag=True, help="exit non zero when something needs a person")
@click.pass_obj
@bench_command
def status(context: Context, fail_on_trouble: bool) -> None:
    """What the whole bench is doing right now."""
    with context.unit() as unit:
        state = overview_service.overview(unit, context.now, settings=context.settings)
    payload = {
        "at": state.at,
        "site": state.site,
        "boards": state.boards,
        "available": state.available,
        "leases_held": state.leases_held,
        "leases_expiring": state.leases_expiring,
        "queue_waiting": state.queue_waiting,
        "queue_longest_wait_seconds": state.queue_longest_wait_seconds,
        "jobs_queued": state.jobs_queued,
        "jobs_running": state.jobs_running,
        "thermal": state.thermal,
        "calibration_overdue": list(state.calibration_overdue),
        "healthy": state.healthy,
    }
    if context.as_json:
        context.say(state.describe(), payload)
    else:
        click.echo(state.describe())
        click.echo("boards   " + (_counts(state.boards) or "none"))
        click.echo(f"leases   {state.leases_held} held, {state.leases_expiring} expiring soon")
        click.echo(f"queue    {state.queue_waiting} waiting")
        click.echo(f"jobs     {state.jobs_queued} queued, {state.jobs_running} running")
        click.echo("thermal  " + (_counts(state.thermal) or "no readings"))
        if state.calibration_overdue:
            click.echo("overdue  " + ", ".join(state.calibration_overdue))
    if fail_on_trouble and not state.healthy:
        raise SystemExit(2)


def _counts(counts: dict[str, int]) -> str:
    return ", ".join(f"{name} {value}" for name, value in counts.items())
