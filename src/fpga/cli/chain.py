"""Scanning a board's chain from the command line and acting on what came back.

This is the command somebody runs when a board has started failing and nobody
knows why yet. It scans, compares against what the inventory says should be on
the header, and prints the difference in the terms the difference is actually
in: a missing answer is a cable, a wrong idcode in the right place is the wrong
part fitted, and the same idcodes in the wrong order is the wrong header.

Nothing moves the board unless --apply is given. A scan that quietly sent a
board to maintenance would make people stop scanning, and a scan is the cheapest
diagnostic on the bench.
"""

from __future__ import annotations

import click

from fpga.cli.main import Context, bench_command, cli
from fpga.drivers import session_for
from fpga.inventory.chains import ChainCheck, chain_for, reconcile
from fpga.jtag.bits import BitVector
from fpga.services import inventory

__all__ = ["chain_group"]


@cli.group("chain")
def chain_group() -> None:
    """What is on the header, and what should be."""


def _capture(context: Context, tag: str, live: bool) -> BitVector:
    """Scan one board, through the real cable or the simulator."""
    with context.unit() as unit:
        board = unit.boards.get(tag)
    with session_for(board, simulated=not live) as session:
        return session.scan_idcodes()


def _apply(context: Context, check: ChainCheck, actor: str) -> str | None:
    """Move the board where the check says it belongs, if anywhere."""
    state = check.next_state()
    if state is None:
        return None
    with context.unit() as unit:
        if state == "faulted":
            inventory.fault_board(unit, check.board, context.now, check.describe(), actor=actor)
        else:
            inventory.move_state(
                unit,
                check.board,
                state,
                context.now,
                reason=check.describe(),
                actor=actor,
            )
    return state


@chain_group.command("show")
@click.argument("tag")
@click.pass_obj
@bench_command
def show_chain(context: Context, tag: str) -> None:
    """The chain this board should present, controllers and all."""
    with context.unit() as unit:
        board = unit.boards.get(tag)
    chain = chain_for(board)
    rows = [
        {
            "position": index,
            "label": tap.label,
            "ir": tap.ir_length,
            "idcode": "-" if tap.expected_idcode is None else f"0x{tap.expected_idcode:08x}",
            "device": tap.device or "-",
        }
        for index, tap in enumerate(chain.taps)
    ]
    context.emit(rows, ("position", "label", "ir", "idcode", "device"))


@chain_group.command("scan")
@click.argument("tag")
@click.option("--live", is_flag=True, help="use the real cable rather than the simulator")
@click.option("--apply", "apply_state", is_flag=True, help="move the board if the scan says to")
@click.option("--actor", default="cli", help="who is scanning")
@click.pass_obj
@bench_command
def scan_chain(context: Context, tag: str, live: bool, apply_state: bool, actor: str) -> None:
    """Scan a board and say whether it answered with what it should have."""
    with context.unit() as unit:
        board = unit.boards.get(tag)
    captured = _capture(context, tag, live)
    check = reconcile(board, captured)
    moved = _apply(context, check, actor) if apply_state else None
    context.say(
        check.describe() if moved is None else f"{check.describe()} ({tag} -> {moved})",
        {
            "board": tag,
            "ok": check.ok,
            "expected": check.expected,
            "found": check.found,
            "retry_worthwhile": check.retry_worthwhile,
            "differences": [difference.describe() for difference in check.differences],
            "moved_to": moved,
        },
    )
    if not check.ok:
        raise SystemExit(2)


@chain_group.command("diagnose")
@click.argument("tag")
@click.argument("capture")
@click.option("--file", "from_file", is_flag=True, help="read the capture out of a file")
@click.pass_obj
@bench_command
def diagnose(context: Context, tag: str, capture: str, from_file: bool) -> None:
    """Explain a capture somebody took somewhere else.

    The capture is the bit string a scan produced, most significant bit first,
    which is what the analyser on the rig writes out. It is worth having as a
    separate command because the interesting captures are the ones taken while
    the cable was still plugged into whatever caused the problem.
    """
    text = click.open_file(capture).read().strip() if from_file else capture
    with context.unit() as unit:
        board = unit.boards.get(tag)
    check = reconcile(board, BitVector.parse(text))
    context.say(
        check.describe(),
        {
            "board": tag,
            "ok": check.ok,
            "expected": check.expected,
            "found": check.found,
            "wiring": check.wiring,
            "would_move_to": check.next_state(),
        },
    )
