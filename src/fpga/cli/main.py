"""The entry point, the shared context, and the board commands.

Two decisions shape everything here.

Errors are printed, not raised. A traceback in front of somebody holding a
ribbon cable is noise; what they need is the one line the bench already wrote
into the error, and a non zero exit code so a script notices. Anything that is
not a bench error still raises, because that is a defect and hiding it helps
nobody.

Every listing takes --json. The default output is columns for a person, and
the json is the same data for a script, and neither is a reformatting of the
other: both come off the same objects.
"""

from __future__ import annotations

import json
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import click

from fpga.config import BenchSettings
from fpga.errors import BenchError
from fpga.persistence.unit import BenchDatabase, UnitOfWork
from fpga.services import inventory

__all__ = ["cli", "run"]


@dataclass
class Context:
    """What every command is handed."""

    settings: BenchSettings
    database: BenchDatabase
    as_json: bool = False

    def unit(self) -> UnitOfWork:
        return self.database.unit()

    @property
    def now(self) -> datetime:
        return datetime.now(UTC)

    def emit(self, rows: Sequence[dict[str, Any]], columns: Sequence[str]) -> None:
        """Print a listing, as json or as columns."""
        if self.as_json:
            click.echo(json.dumps(list(rows), indent=2, default=str))
            return
        if not rows:
            click.echo("nothing to show")
            return
        widths = {
            name: max(len(name), max(len(str(row.get(name, ""))) for row in rows))
            for name in columns
        }
        click.echo("  ".join(name.upper().ljust(widths[name]) for name in columns))
        for row in rows:
            click.echo("  ".join(str(row.get(name, "")).ljust(widths[name]) for name in columns))

    def say(self, message: str, payload: dict[str, Any] | None = None) -> None:
        """Print one result, as json or as a sentence."""
        if self.as_json:
            click.echo(json.dumps(payload or {"message": message}, indent=2, default=str))
        else:
            click.echo(message)


def bench_command(function: Callable[..., Any]) -> Callable[..., Any]:
    """Turn a bench error into a printed line and a non zero exit."""

    def wrapper(*args: Any, **kwargs: Any) -> Any:
        try:
            return function(*args, **kwargs)
        except BenchError as failure:
            raise click.ClickException(str(failure)) from failure

    wrapper.__name__ = function.__name__
    wrapper.__doc__ = function.__doc__
    return wrapper


@click.group()
@click.option("--json", "as_json", is_flag=True, help="print machine readable output")
@click.option("--database", "database_url", default=None, help="override the database url")
@click.pass_context
def cli(context: click.Context, as_json: bool, database_url: str | None) -> None:
    """The bench: boards, leases, images and jobs."""
    settings = BenchSettings.from_env()
    if database_url:
        settings = settings.override(database_url=database_url)
    context.obj = Context(
        settings=settings,
        database=BenchDatabase(settings),
        as_json=as_json,
    )


@cli.command("version")
@click.pass_obj
def version(context: Context) -> None:
    """What this is and which site it thinks it belongs to."""
    from fpga import __version__

    context.say(
        f"fpga bench {__version__} at {context.settings.site}",
        {"version": __version__, "site": context.settings.site},
    )


@cli.group("boards")
def boards_group() -> None:
    """What is on the bench."""


@boards_group.command("list")
@click.option("--available", is_flag=True, help="only boards nobody is using")
@click.option("--rack", default=None, help="one rack rather than the whole bench")
@click.pass_obj
@bench_command
def list_boards(context: Context, available: bool, rack: str | None) -> None:
    """Every board, with the state it is in."""
    with context.unit() as unit:
        if rack is not None:
            boards = unit.boards.in_rack(rack)
        else:
            boards = inventory.list_boards(unit, only_available=available)
        rows = [
            {
                "asset": board.tag,
                "model": board.model,
                "device": board.device().device,
                "location": str(board.location),
                "state": unit.boards.state(board.tag).state,
                "cards": ",".join(spec.name for spec in board.fitted_cards()) or "-",
            }
            for board in boards
        ]
    context.emit(rows, ("asset", "model", "device", "location", "state", "cards"))


@boards_group.command("show")
@click.argument("tag")
@click.pass_obj
@bench_command
def show_board(context: Context, tag: str) -> None:
    """One board, in as much detail as the bench has."""
    with context.unit() as unit:
        board = unit.boards.get(tag)
        state = unit.boards.state(tag)
        held = unit.leases.for_board(tag, context.now)
        payload = {
            "asset": board.tag,
            "model": board.model,
            "revision": board.revision,
            "part": board.part.ordering_code(),
            "device": board.device().device,
            "flash": board.flash,
            "location": str(board.location),
            "cards": [spec.name for spec in board.fitted_cards()],
            "transceivers_free": board.transceivers_free,
            "adapter": None if board.adapter is None else board.adapter.describe(),
            "pdu": None if board.pdu is None else str(board.pdu),
            "state": state.state,
            "state_reason": state.reason,
            "held_by": None if held is None else held.holder,
        }
    if context.as_json:
        click.echo(json.dumps(payload, indent=2, default=str))
        return
    click.echo(board.describe())
    click.echo(f"state    {state.describe()}")
    click.echo(f"held by  {payload['held_by'] or 'nobody'}")
    click.echo(f"cable    {payload['adapter'] or 'none'}")
    click.echo(f"outlet   {payload['pdu'] or 'none'}")


@boards_group.command("state")
@click.argument("tag")
@click.argument("state")
@click.option("--reason", default="", help="why, which some states insist on")
@click.option("--actor", default="cli", help="who is doing it")
@click.pass_obj
@bench_command
def set_state(context: Context, tag: str, state: str, reason: str, actor: str) -> None:
    """Move a board's state."""
    with context.unit() as unit:
        if state == "faulted":
            moved, released = inventory.fault_board(
                unit,
                tag,
                context.now,
                reason or "faulted from the command line",
                actor=actor,
            )
            given_back = None if released is None else released.id
        else:
            moved = inventory.move_state(
                unit,
                tag,
                state,
                context.now,
                reason=reason,
                actor=actor,
            )
            given_back = None
    message = f"{tag} is now {moved.state}"
    if given_back:
        message = f"{message}, {given_back} released"
    context.say(message, {"board": tag, "state": moved.state, "released": given_back})


def run() -> None:  # pragma: no cover - the console script entry point
    """The console script, which pulls in every command group first.

    The groups live in their own modules and register themselves on import, so
    something has to import them. Doing it here rather than at the top of this
    module keeps the import graph acyclic: they import this for the context.
    """
    from fpga.cli import (  # noqa: F401
        chain,
        flash,
        images,
        jobs,
        leases,
        power,
        queue,
        thermal,
        upkeep,
    )

    cli()
