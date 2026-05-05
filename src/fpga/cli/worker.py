"""The loop that actually gets work done on a bench nobody is watching.

It does three things in order and the order is the point. Recovery first,
because a job left running by the last crash is holding a board that nothing
else will release. Then the waiting list, because a request that has been
waiting an hour should get a board before a job that was queued a minute ago
gets run on one. Then the jobs.

There is no daemon here. The loop runs a fixed number of rounds and exits, and
whatever runs it on a timer decides how often that is. A worker that supervises
itself is a worker that has to be supervised.
"""

from __future__ import annotations

import click

from fpga.cli.main import Context, bench_command, cli
from fpga.inventory.faults import FaultLog
from fpga.jobs.recovery import RecoveryReport
from fpga.services import execution, recovery
from fpga.services import scheduling as queue_service

__all__ = ["worker_group"]


@cli.group("worker")
def worker_group() -> None:
    """Running the bench without anybody at it."""


@worker_group.command("recover")
@click.pass_obj
@bench_command
def recover(context: Context) -> None:
    """Close out whatever was in flight when this bench last stopped."""
    journal = execution.journal_for(context.settings)
    with context.unit() as unit:
        report = recovery.recover(unit, journal, context.now, settings=context.settings)
    if report.clean:
        context.say("nothing was interrupted", {"recovered": []})
        return
    context.emit(_rows_of(report), ("what", "subject"))


def _rows_of(report: RecoveryReport) -> list[dict[str, str]]:
    """One row per thing recovery did, jobs first and then boards."""
    return [
        {"what": what, "subject": subject}
        for what, names in (
            ("requeued", report.jobs_requeued),
            ("closed", report.jobs_closed),
            ("faulted", report.boards_faulted),
            ("returned", report.boards_returned),
            ("unknown", report.unknown_jobs),
        )
        for subject in names
    ]


@worker_group.command("run")
@click.option("--rounds", default=1, help="how many times round the loop")
@click.option("--live", is_flag=True, help="use the real cables rather than the simulator")
@click.option("--no-dispatch", is_flag=True, help="run jobs but do not hand out boards")
@click.option("--no-recover", is_flag=True, help="skip the recovery pass at the start")
@click.pass_obj
@bench_command
def run(context: Context, rounds: int, live: bool, no_dispatch: bool, no_recover: bool) -> None:
    """Recover, hand out boards, then run whatever jobs are ready."""
    if rounds < 1:
        raise click.UsageError("a worker runs at least one round")
    journal = execution.journal_for(context.settings)
    faults = FaultLog()
    recovered: list[str] = []
    assigned: list[str] = []
    ran: list[dict[str, str]] = []
    with context.unit() as unit:
        if not no_recover:
            report = recovery.recover(unit, journal, context.now, settings=context.settings)
            recovered = [] if report.clean else [report.describe()]
        for _ in range(rounds):
            if not no_dispatch:
                outcome = queue_service.dispatch(unit, context.now, settings=context.settings)
                assigned += [f"{request} -> {board}" for request, board, _ in outcome.assigned]
            job = execution.next_ready(unit, context.now)
            if job is None:
                break
            result = execution.run_job(
                unit,
                job,
                context.now,
                settings=context.settings,
                journal=journal,
                faults=faults,
                live=live,
            )
            ran.append(
                {
                    "job": result.job.id,
                    "asset": result.job.board,
                    "state": result.job.state,
                    "detail": result.detail,
                }
            )
    if context.as_json:
        context.say(
            "worker finished",
            {"recovered": recovered, "assigned": assigned, "ran": ran},
        )
        return
    for line in recovered:
        click.echo(f"recovered {line}")
    for line in assigned:
        click.echo(f"assigned  {line}")
    for entry in ran:
        click.echo(f"ran       {entry['job']} {entry['state']}: {entry['detail']}")
    if not recovered and not assigned and not ran:
        click.echo("nothing to do")
