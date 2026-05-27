"""Taking a board and giving it back, from a terminal beside the rack.

The one command worth explaining is take. It can be handed a board, or it can
be handed what the job needs and pick one, and the second form is what people
actually use: nobody wants bx-14, they want something with a converter on it
that nobody else is using.
"""

from __future__ import annotations

from datetime import timedelta

import click

from fpga.capabilities import RequirementSet
from fpga.cli.main import Context, bench_command, cli
from fpga.errors import Conflict
from fpga.inventory.profile import board_profile
from fpga.scheduling.matcher import choose
from fpga.services import leasing

__all__ = ["leases_group"]


@cli.group("lease")
def leases_group() -> None:
    """Who has which board."""


@leases_group.command("list")
@click.option("--holder", default=None, help="one team rather than all of them")
@click.pass_obj
@bench_command
def list_leases(context: Context, holder: str | None) -> None:
    """Every board being held right now."""
    with context.unit() as unit:
        held = (
            unit.leases.held_by(holder, context.now) if holder else unit.leases.active(context.now)
        )
        rows = [
            {
                "lease": lease.id,
                "board": lease.board,
                "holder": lease.holder,
                "purpose": lease.purpose,
                "until": lease.expires_at.isoformat(timespec="minutes"),
                "priority": lease.priority,
            }
            for lease in held
        ]
    context.emit(rows, ("lease", "board", "holder", "purpose", "until", "priority"))


@leases_group.command("take")
@click.option("--board", "tag", default=None, help="a particular board")
@click.option("--needs", "wanted", default=None, help="what the job needs, comma separated")
@click.option("--holder", required=True, help="who is taking it")
@click.option("--purpose", required=True, help="what it is for")
@click.option("--minutes", default=None, type=int, help="how long, up to the ceiling")
@click.option("--priority", default=5, type=int, help="one is most urgent")
@click.option("--keep", is_flag=True, help="refuse to be preempted")
@click.pass_obj
@bench_command
def take(
    context: Context,
    tag: str | None,
    wanted: str | None,
    holder: str,
    purpose: str,
    minutes: int | None,
    priority: int,
    keep: bool,
) -> None:
    """Take a board, either by name or by what it has to be able to do."""
    if not tag and not wanted:
        raise click.UsageError("say which board, or what it needs")
    with context.unit() as unit:
        if not tag:
            requirements = RequirementSet.parse(wanted or "")
            free = unit.boards.available()
            picked = choose(free, requirements, now=context.now)
            if picked is None:
                reasons = {board.tag: requirements.why_not(board_profile(board)) for board in free}
                raise Conflict(
                    "nothing free matches what you asked for",
                    wanted=wanted or "",
                    considered="; ".join(f"{tag}: {why}" for tag, why in reasons.items()),
                )
            tag = picked.tag
        lease = leasing.acquire(
            unit,
            tag,
            holder,
            purpose,
            context.now,
            minutes=minutes,
            priority=priority,
            preemptible=not keep,
            settings=context.settings,
        )
    context.say(
        f"{lease.board} is yours until {lease.expires_at.isoformat(timespec='minutes')}"
        f" ({lease.id})",
        {
            "lease": lease.id,
            "board": lease.board,
            "holder": lease.holder,
            "expires_at": lease.expires_at,
        },
    )


@leases_group.command("release")
@click.argument("lease_id")
@click.option("--reason", default="released", help="why it is being given back")
@click.pass_obj
@bench_command
def release(context: Context, lease_id: str, reason: str) -> None:
    """Give a board back."""
    with context.unit() as unit:
        closed = leasing.release(unit, lease_id, context.now, reason)
    context.say(
        f"{closed.board} is free again",
        {"lease": closed.id, "board": closed.board, "reason": reason},
    )


@leases_group.command("renew")
@click.argument("lease_id")
@click.option("--minutes", default=60, type=int, help="how much longer")
@click.pass_obj
@bench_command
def renew(context: Context, lease_id: str, minutes: int) -> None:
    """Keep a board a while longer, up to the ceiling."""
    with context.unit() as unit:
        renewed = leasing.renew(
            unit,
            lease_id,
            context.now,
            minutes,
            settings=context.settings,
        )
    context.say(
        f"{renewed.board} is yours until {renewed.expires_at.isoformat(timespec='minutes')}",
        {"lease": renewed.id, "board": renewed.board, "expires_at": renewed.expires_at},
    )


@leases_group.command("sweep")
@click.pass_obj
@bench_command
def sweep(context: Context) -> None:
    """Close the leases that have run out and free their boards."""
    with context.unit() as unit:
        closed = leasing.sweep_expired(unit, context.now)
    rows = [{"lease": lease.id, "board": lease.board, "holder": lease.holder} for lease in closed]
    if not rows:
        context.say("nothing had run out", {"closed": []})
        return
    context.emit(rows, ("lease", "board", "holder"))


@leases_group.command("expiring")
@click.option("--minutes", default=15, help="how far ahead to look")
@click.option("--remind", "write_reminder", is_flag=True, help="write a line into the trail")
@click.pass_obj
@bench_command
def expiring(context: Context, minutes: int, write_reminder: bool) -> None:
    """Leases about to run out, soonest first."""
    window = timedelta(minutes=minutes)
    with context.unit() as unit:
        found = (
            leasing.remind(unit, context.now, window)
            if write_reminder
            else leasing.expiring(unit, context.now, window)
        )
        rows = [
            {
                "lease": lease.id,
                "asset": lease.board,
                "holder": lease.holder,
                "left": f"{int(lease.remaining(context.now).total_seconds() // 60)}m",
                "renewals": lease.renewals,
            }
            for lease in found
        ]
    context.emit(rows, ("lease", "asset", "holder", "left", "renewals"))
