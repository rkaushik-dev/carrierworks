"""Temperatures from the command line: feed them in, read them back, act.

The record command is the one that gets called from somewhere else. The poller
on each rack controller reads its own boards and shells out to this, because
that was a two line change on the controllers and a new daemon was not.

The tick command is the whole supervisor pass and it does act: a board over
the shutdown line loses its power, its lease and its place in service. Nothing
else here changes anything, so status is safe to run in a loop while somebody
watches a board warm up.
"""

from __future__ import annotations

from datetime import timedelta

import click

from fpga.cli.main import Context, bench_command, cli
from fpga.services import thermal as thermal_service
from fpga.temperature import parse_temperature, render_temperature

__all__ = ["thermal_group"]


@cli.group("thermal")
def thermal_group() -> None:
    """Readings, alarm states and the supervisor pass."""


@thermal_group.command("record")
@click.argument("tag")
@click.argument("temperature")
@click.option("--source", default="sysmon", help="which sensor this came off")
@click.pass_obj
@bench_command
def record(context: Context, tag: str, temperature: str, source: str) -> None:
    """Store one reading, in celsius or millidegrees."""
    millidegrees = parse_temperature(temperature)
    with context.unit() as unit:
        sample = thermal_service.record(unit, tag, context.now, millidegrees, source=source)
    context.say(
        sample.describe(),
        {
            "board": sample.board,
            "at": sample.at,
            "millidegrees": sample.millidegrees,
            "source": sample.source,
        },
    )


@thermal_group.command("status")
@click.option("--window", default=15, help="how many minutes of readings to look at")
@click.option("--alarming", is_flag=True, help="only the boards worth waking somebody for")
@click.pass_obj
@bench_command
def status(context: Context, window: int, alarming: bool) -> None:
    """Where every board stands, worst first, changing nothing."""
    with context.unit() as unit:
        statuses = thermal_service.status_of(
            unit,
            context.now,
            settings=context.settings,
            window=timedelta(minutes=window),
        )
        rows = [
            {
                "asset": entry.board,
                "state": entry.state,
                "reading": (
                    "-" if entry.millidegrees is None else render_temperature(entry.millidegrees)
                ),
                "for": str(entry.held_for(context.now)).split(".")[0],
                "stale": "yes" if entry.stale else "no",
            }
            for entry in statuses
            if not alarming or entry.alarming
        ]
    context.emit(rows, ("asset", "state", "reading", "for", "stale"))


@thermal_group.command("history")
@click.argument("tag")
@click.option("--window", default=60, help="how many minutes back to go")
@click.pass_obj
@bench_command
def history(context: Context, tag: str, window: int) -> None:
    """Every reading for one board, oldest first."""
    with context.unit() as unit:
        unit.boards.get(tag)
        samples = unit.thermal.since(context.now - timedelta(minutes=window), tag)
        rows = [
            {
                "at": sample.at.isoformat(timespec="seconds"),
                "reading": render_temperature(sample.millidegrees),
                "source": sample.source,
            }
            for sample in samples
        ]
    context.emit(rows, ("at", "reading", "source"))


@thermal_group.command("tick")
@click.option("--window", default=15, help="how many minutes of readings to look at")
@click.pass_obj
@bench_command
def tick(context: Context, window: int) -> None:
    """One supervisor pass, which will shut a board down if it has to."""
    with context.unit() as unit:
        actions = thermal_service.tick(
            unit,
            context.now,
            settings=context.settings,
            window=timedelta(minutes=window),
        )
        rows = [
            {
                "asset": action.board,
                "action": action.action,
                "reason": action.reason,
                "downwind": ",".join(action.affected) or "-",
            }
            for action in actions
        ]
    if not rows:
        context.say("nothing to do", {"actions": []})
        return
    context.emit(rows, ("asset", "action", "reason", "downwind"))


@thermal_group.command("prune")
@click.option("--keep-days", default=7, help="how much history to keep")
@click.pass_obj
@bench_command
def prune(context: Context, keep_days: int) -> None:
    """Drop readings older than the retention window."""
    with context.unit() as unit:
        dropped = thermal_service.prune(unit, context.now, timedelta(days=keep_days))
    context.say(
        f"dropped {dropped} readings older than {keep_days} days",
        {"dropped": dropped, "keep_days": keep_days},
    )
