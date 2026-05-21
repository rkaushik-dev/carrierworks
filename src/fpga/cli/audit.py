"""The audit trail from the command line, which is where it gets read.

Somebody asks what happened to a board overnight, and the answer is one
command with a board tag in it. The filters combine, so the follow up question,
which is always what else that script touched, is the same command with the
actor instead.

Times are printed to the second in utc. The bench writes everything in utc and
converting for display here would mean two different clocks in the same
answer, since the detail lines carry their own timestamps.
"""

from __future__ import annotations

from datetime import timedelta

import click

from fpga.cli.main import Context, bench_command, cli
from fpga.services import audit as audit_service

__all__ = ["audit_group"]


@cli.command("audit")
@click.option("--subject", default=None, help="one board, image or lease")
@click.option("--actor", default=None, help="one person or script")
@click.option("--action", default=None, help="one kind of thing done")
@click.option("--hours", default=0, help="nothing older than this many hours")
@click.option("--limit", default=50, help="how many lines at most")
@click.pass_obj
@bench_command
def audit_group(
    context: Context,
    subject: str | None,
    actor: str | None,
    action: str | None,
    hours: int,
    limit: int,
) -> None:
    """Who did what, newest first."""
    if limit < 1:
        raise click.UsageError("ask for at least one line")
    since = context.now - timedelta(hours=hours) if hours else None
    with context.unit() as unit:
        found = audit_service.search(
            unit,
            subject=subject,
            actor=actor,
            action=action,
            since=since,
            limit=limit,
        )
        rows = [
            {
                "at": entry.at.isoformat(timespec="seconds"),
                "actor": entry.actor,
                "action": entry.action,
                "subject": entry.subject or "-",
                "detail": entry.detail or "-",
            }
            for entry in found
        ]
    context.emit(rows, ("at", "actor", "action", "subject", "detail"))
