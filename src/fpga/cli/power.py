"""Outlets and rails from the command line, with the interlocks in front.

Two different things are called power here and they are worth keeping apart.
The outlet is the thing the distribution unit switches, and it is all or
nothing. The rails are what the carrier does after the outlet comes up, in the
order the datasheets want, and the bench only ever plans those: no board in
this lab exposes rail control to software.

So the on and cycle commands really switch an outlet, and the plan command
prints the rail sequence the carrier will run through on its own. Both go
through the same interlocks, because the reason not to touch a board is the
same either way: somebody has their hands in it, or somebody else is using it.
"""

from __future__ import annotations

import click

from fpga.cli.main import Context, bench_command, cli
from fpga.errors import PowerFault
from fpga.inventory.boards import Board
from fpga.inventory.state import BoardState
from fpga.leases.models import Lease
from fpga.power.pdu import FakePdu, PduDriver, PowerController
from fpga.power.rails import tree_for_model
from fpga.power.sequence import Interlock, power_down_plan, power_up_plan

__all__ = ["power_group"]

_DRIVERS: dict[str, PduDriver] = {}


def register_pdu(name: str, driver: PduDriver) -> None:
    """Point a rack's distribution unit at whatever drives it."""
    _DRIVERS[name] = driver


def driver_for(board: Board, *, simulated: bool) -> PduDriver:
    """The unit this board hangs off, or a fake one that answers the same."""
    if board.pdu is None:
        raise PowerFault("that board is not on a switched outlet", board=board.tag)
    if simulated:
        return FakePdu(outlets={str(board.pdu): True})
    found = _DRIVERS.get(board.pdu.pdu)
    if found is None:
        raise PowerFault("no driver for that distribution unit", pdu=board.pdu.pdu)
    return found


def interlocks(state: BoardState, lease: Lease | None, *, holder: str) -> tuple[Interlock, ...]:
    """What would stop somebody switching this board, and why.

    Maintenance is the hard one and it is deliberately not overridable from
    here: a board in maintenance is a board with a person at it, and the way
    to power it is to take it out of maintenance first, which leaves a line in
    the audit trail with a name on it.
    """
    held_by_somebody_else = lease is not None and lease.holder != holder
    return (
        Interlock(
            name="maintenance",
            engaged=state.state == "maintenance",
            reason="somebody is working on the board",
        ),
        Interlock(
            name="lease",
            engaged=held_by_somebody_else,
            reason="" if lease is None else f"{lease.holder} is using it",
        ),
    )


@cli.group("power")
def power_group() -> None:
    """Outlets, rails and the interlocks in front of both."""


def _board_and_locks(
    context: Context, tag: str, holder: str
) -> tuple[Board, tuple[Interlock, ...]]:
    with context.unit() as unit:
        board = unit.boards.get(tag)
        state = unit.boards.state(tag)
        lease = unit.leases.for_board(tag, context.now)
    return board, interlocks(state, lease, holder=holder)


def _engaged(locks: tuple[Interlock, ...]) -> tuple[str, ...]:
    return tuple(lock.describe() for lock in locks if lock.engaged)


@power_group.command("tree")
@click.argument("tag")
@click.pass_obj
@bench_command
def show_tree(context: Context, tag: str) -> None:
    """The rails on this carrier, in the order they come up."""
    with context.unit() as unit:
        board = unit.boards.get(tag)
    tree = tree_for_model(board.model)
    rows = [
        {
            "rail": step.name,
            "mv": step.rail.nominal_mv,
            "max_ma": step.rail.max_current_ma,
            "after": ",".join(step.depends_on) or "-",
            "settle_ms": step.settle_ms,
        }
        for step in tree.order_up()
    ]
    context.emit(rows, ("rail", "mv", "max_ma", "after", "settle_ms"))


@power_group.command("plan")
@click.argument("tag")
@click.option("--down", "going_down", is_flag=True, help="the shutdown order instead")
@click.option("--holder", default="cli", help="who is asking, for the lease interlock")
@click.pass_obj
@bench_command
def show_plan(context: Context, tag: str, going_down: bool, holder: str) -> None:
    """What the carrier will do to its rails, and what would stop it."""
    board, locks = _board_and_locks(context, tag, holder)
    tree = tree_for_model(board.model)
    plan = (
        power_down_plan(tree, interlocks=locks)
        if going_down
        else power_up_plan(tree, interlocks=locks)
    )
    if context.as_json:
        context.emit(
            [{"action": step.action, "rail": step.rail, "ms": step.millis} for step in plan.steps],
            ("action", "rail", "ms"),
        )
        return
    click.echo(plan.describe())
    for step in plan.steps:
        click.echo(f"  {step.describe()}")


@power_group.command("show")
@click.argument("tag")
@click.option("--live", is_flag=True, help="ask the real distribution unit")
@click.option("--holder", default="cli", help="who is asking, for the lease interlock")
@click.pass_obj
@bench_command
def show_outlet(context: Context, tag: str, live: bool, holder: str) -> None:
    """Whether the outlet is on, and what it is drawing."""
    board, locks = _board_and_locks(context, tag, holder)
    driver = driver_for(board, simulated=not live)
    controller = PowerController(driver)
    on = controller.is_on(board)
    drawn = driver.current_ma(board.pdu) if board.pdu is not None else 0
    context.say(
        f"{tag} is {'on' if on else 'off'} at {board.pdu}, drawing {drawn}mA",
        {
            "board": tag,
            "outlet": str(board.pdu),
            "pdu": driver.name,
            "on": on,
            "current_ma": drawn,
            "blocked_by": list(_engaged(locks)),
        },
    )


def _switch(context: Context, tag: str, holder: str, live: bool, force: bool, on: bool) -> None:
    board, locks = _board_and_locks(context, tag, holder)
    blocked = _engaged(locks)
    if blocked and not force:
        raise PowerFault("that board is interlocked", board=tag, reasons="; ".join(blocked))
    controller = PowerController(driver_for(board, simulated=not live))
    changed = controller.power_on(board) if on else controller.power_off(board)
    word = "on" if on else "off"
    context.say(
        f"{tag} {word}" if changed else f"{tag} was already {word}",
        {"board": tag, "on": on, "changed": changed, "blocked_by": list(blocked)},
    )


@power_group.command("on")
@click.argument("tag")
@click.option("--live", is_flag=True, help="switch the real outlet")
@click.option("--force", is_flag=True, help="switch it even though something is engaged")
@click.option("--holder", default="cli", help="who is asking, for the lease interlock")
@click.pass_obj
@bench_command
def power_on(context: Context, tag: str, live: bool, force: bool, holder: str) -> None:
    """Switch a board's outlet on."""
    _switch(context, tag, holder, live, force, on=True)


@power_group.command("off")
@click.argument("tag")
@click.option("--live", is_flag=True, help="switch the real outlet")
@click.option("--force", is_flag=True, help="switch it even though something is engaged")
@click.option("--holder", default="cli", help="who is asking, for the lease interlock")
@click.pass_obj
@bench_command
def power_off(context: Context, tag: str, live: bool, force: bool, holder: str) -> None:
    """Switch a board's outlet off."""
    _switch(context, tag, holder, live, force, on=False)


@power_group.command("cycle")
@click.argument("tag")
@click.option("--off-ms", default=None, type=int, help="how long to leave it down")
@click.option("--live", is_flag=True, help="switch the real outlet")
@click.option("--force", is_flag=True, help="cycle it even though something is engaged")
@click.option("--holder", default="cli", help="who is asking, for the lease interlock")
@click.pass_obj
@bench_command
def cycle(
    context: Context,
    tag: str,
    off_ms: int | None,
    live: bool,
    force: bool,
    holder: str,
) -> None:
    """Off, wait, on, which is what fixes a board that stopped answering."""
    board, locks = _board_and_locks(context, tag, holder)
    blocked = _engaged(locks)
    if blocked and not force:
        raise PowerFault("that board is interlocked", board=tag, reasons="; ".join(blocked))
    controller = PowerController(driver_for(board, simulated=not live))
    waited = controller.cycle(board, off_ms=off_ms)
    context.say(
        f"{tag} cycled, down for {waited}ms",
        {"board": tag, "off_ms": waited, "blocked_by": list(blocked)},
    )
