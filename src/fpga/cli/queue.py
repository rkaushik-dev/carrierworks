"""The waiting list from the command line.

Somebody asks for a board by what it has to do, and either gets one now or
gets a place in the line. The two commands people actually use are submit and
list: submit because it is the polite alternative to taking a board somebody
else is about to need, and list because the first question after waiting ten
minutes is where you are in the queue.

Dispatch is the pass that hands boards out, and it is not on a timer here. A
site runs it from cron, or after every release, or by hand when somebody gives
a board back early.
"""

from __future__ import annotations

import click

from fpga.cli.main import Context, bench_command, cli
from fpga.scheduling.queue import Request
from fpga.services import scheduling as queue_service

__all__ = ["queue_group"]


@cli.group("queue")
def queue_group() -> None:
    """Requests waiting for a board."""


@queue_group.command("submit")
@click.option("--holder", required=True, help="who is asking")
@click.option("--purpose", required=True, help="what it is for")
@click.option("--needs", "wants", default="", help="requirements, comma separated")
@click.option("--priority", default=5, help="one is urgent, nine is whenever")
@click.option("--minutes", default=60, help="how long the lease should run for")
@click.option("--no-preempt", is_flag=True, help="refuse to be bumped by anything")
@click.option("--needs-hands", is_flag=True, help="somebody has to be in the lab for this")
@click.pass_obj
@bench_command
def submit(
    context: Context,
    holder: str,
    purpose: str,
    wants: str,
    priority: int,
    minutes: int,
    no_preempt: bool,
    needs_hands: bool,
) -> None:
    """Ask for a board and take a place in the line."""
    with context.unit() as unit:
        request = queue_service.submit(
            unit,
            holder,
            purpose,
            wants,
            context.now,
            priority=priority,
            minutes=minutes,
            preemptible=not no_preempt,
            needs_hands=needs_hands,
        )
        ahead = len(
            [entry for entry in unit.requests.ordered(context.now) if entry.id != request.id]
        )
    context.say(
        f"{request.id} queued, {ahead} ahead of it",
        {"request": request.id, "holder": request.holder, "ahead": ahead},
    )


@queue_group.command("list")
@click.option("--holder", default=None, help="one team rather than everybody")
@click.option("--all", "everything", is_flag=True, help="closed requests as well")
@click.pass_obj
@bench_command
def list_requests(context: Context, holder: str | None, everything: bool) -> None:
    """What is waiting, in the order it will be served."""
    with context.unit() as unit:
        if holder is not None:
            found = unit.requests.for_holder(holder)
        elif everything:
            found = unit.requests.all()
        else:
            found = unit.requests.ordered(context.now)
        rows = [
            {
                "request": entry.id,
                "holder": entry.holder,
                "priority": entry.priority,
                "needs": ",".join(item.text for item in entry.requirements) or "anything",
                "waited": str(entry.waited(context.now)).split(".")[0],
                "state": _state_of(entry),
                "board": unit.requests.board_for(entry.id) or "-",
            }
            for entry in found
        ]
    context.emit(rows, ("request", "holder", "priority", "needs", "waited", "state", "board"))


def _state_of(request: Request) -> str:
    if request.satisfied_at is not None:
        return "served"
    if request.cancelled_at is not None:
        return "cancelled"
    return "waiting"


@queue_group.command("cancel")
@click.argument("request_id")
@click.pass_obj
@bench_command
def cancel(context: Context, request_id: str) -> None:
    """Give up a place in the line."""
    with context.unit() as unit:
        dropped = queue_service.cancel(unit, request_id, context.now)
    context.say(f"{dropped.id} cancelled", {"request": dropped.id, "state": "cancelled"})


@queue_group.command("dispatch")
@click.option("--limit", default=0, help="hand out at most this many boards")
@click.option("--quota", "quotas", multiple=True, help="team=count, repeatable")
@click.pass_obj
@bench_command
def dispatch(context: Context, limit: int, quotas: tuple[str, ...]) -> None:
    """One pass over the list, taking every board it can."""
    with context.unit() as unit:
        outcome = queue_service.dispatch(
            unit,
            context.now,
            settings=context.settings,
            quotas=_quotas(quotas),
            limit=limit,
        )
    context.say(
        outcome.describe(),
        {
            "assigned": [
                {"request": request, "board": board, "lease": lease}
                for request, board, lease in outcome.assigned
            ],
            "waiting": outcome.refusals,
        },
    )


def _quotas(given: tuple[str, ...]) -> dict[str, int]:
    """Parse the repeatable team=count option."""
    parsed: dict[str, int] = {}
    for item in given:
        team, _, count = item.partition("=")
        if not team or not count.isdigit():
            raise click.UsageError(f"a quota looks like team=count, not {item}")
        parsed[team.strip().lower()] = int(count)
    return parsed
