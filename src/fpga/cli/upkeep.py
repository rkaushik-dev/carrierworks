"""The maintenance log from the command line.

Somebody finishes work on a board and has to do two things: write down what
they did, and put the board back. The done command does both, because the
board that stays in maintenance for a week is nearly always the one where the
second command got forgotten.

The due command is the one to run on a Monday. It lists what has run out of
calibration, and a board that has never been calibrated counts as run out
rather than as unknown.
"""

from __future__ import annotations

import click

from fpga.cli.main import Context, bench_command, cli
from fpga.inventory.maintenance import MAINTENANCE_KINDS
from fpga.services import upkeep

__all__ = ["upkeep_group"]


@cli.group("upkeep")
def upkeep_group() -> None:
    """Visits, calibration and getting boards back in service."""


@upkeep_group.command("log")
@click.argument("tag")
@click.option("--kind", default="repair", help=f"one of {', '.join(sorted(MAINTENANCE_KINDS))}")
@click.option("--by", required=True, help="who did the work")
@click.option("--notes", default="", help="what was done")
@click.option("--cleared", default=0, help="how many faults this fixed")
@click.pass_obj
@bench_command
def log(context: Context, tag: str, kind: str, by: str, notes: str, cleared: int) -> None:
    """Write down a visit without moving the board."""
    with context.unit() as unit:
        record = upkeep.log_visit(
            unit,
            tag,
            kind,
            context.now,
            by,
            notes=notes,
            faults_cleared=cleared,
        )
    context.say(
        record.describe(),
        {"board": record.board, "kind": record.kind, "by": record.by, "at": record.at},
    )


@upkeep_group.command("done")
@click.argument("tag")
@click.option("--kind", default="repair", help=f"one of {', '.join(sorted(MAINTENANCE_KINDS))}")
@click.option("--by", required=True, help="who did the work")
@click.option("--notes", default="", help="what was done")
@click.option("--cleared", default=0, help="how many faults this fixed")
@click.pass_obj
@bench_command
def done(context: Context, tag: str, kind: str, by: str, notes: str, cleared: int) -> None:
    """Log the visit and put the board back in service."""
    with context.unit() as unit:
        record = upkeep.return_to_service(
            unit,
            tag,
            context.now,
            by,
            kind=kind,
            notes=notes,
            faults_cleared=cleared,
        )
    context.say(
        f"{record.describe()}, {tag} is available",
        {"board": record.board, "kind": record.kind, "state": "available"},
    )


@upkeep_group.command("history")
@click.argument("tag", required=False)
@click.pass_obj
@bench_command
def history(context: Context, tag: str | None) -> None:
    """Every visit, to one board or to the whole bench."""
    with context.unit() as unit:
        if tag is None:
            found = unit.maintenance.all()
        else:
            unit.boards.get(tag)
            found = unit.maintenance.for_board(tag)
        rows = [
            {
                "asset": record.board,
                "kind": record.kind,
                "at": record.at.date().isoformat(),
                "by": record.by,
                "cleared": record.faults_cleared,
                "notes": record.notes or "-",
            }
            for record in found
        ]
    context.emit(rows, ("asset", "kind", "at", "by", "cleared", "notes"))


@upkeep_group.command("calibration")
@click.argument("tag")
@click.pass_obj
@bench_command
def calibration(context: Context, tag: str) -> None:
    """Where one board stands on calibration."""
    with context.unit() as unit:
        state = upkeep.calibration(unit, tag, context.now)
    context.say(
        state.describe(),
        {
            "board": state.board,
            "required": state.required,
            "last_at": state.last_at,
            "due_at": state.due_at,
            "valid": state.valid,
            "never": state.never,
        },
    )


@upkeep_group.command("due")
@click.pass_obj
@bench_command
def due(context: Context) -> None:
    """Every board whose calibration has run out or never happened."""
    with context.unit() as unit:
        rows = [
            {
                "asset": state.board,
                "last": "never" if state.last_at is None else state.last_at.date().isoformat(),
                "due": "-" if state.due_at is None else state.due_at.date().isoformat(),
            }
            for state in upkeep.overdue(unit, context.now)
        ]
    context.emit(rows, ("asset", "last", "due"))
