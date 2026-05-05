"""Programming jobs from the command line, including the dry run people use.

The run command is the one that touches hardware, so it defaults to not
touching any: without --live it runs the whole job against the simulator and
the fake flash, which checks the gates, the image, the chain the board should
present and the plan, and changes nothing. That is what somebody does before
letting a golden image anywhere near a board at four in the afternoon.
"""

from __future__ import annotations

import click

from fpga.cli.main import Context, bench_command, cli
from fpga.services import execution, programming

__all__ = ["jobs_group"]


@cli.group("job")
def jobs_group() -> None:
    """Getting images onto boards."""


@jobs_group.command("submit")
@click.option("--board", "tag", required=True, help="which board")
@click.option("--image", "digest", required=True, help="which image")
@click.option("--mode", default="jtag", help="jtag, flash-update or flash-golden")
@click.option("--by", "requested_by", required=True, help="who is asking")
@click.option("--no-verify", is_flag=True, help="skip the read back after a flash write")
@click.pass_obj
@bench_command
def submit(
    context: Context,
    tag: str,
    digest: str,
    mode: str,
    requested_by: str,
    no_verify: bool,
) -> None:
    """Put a programming job on the queue."""
    with context.unit() as unit:
        job = programming.submit(
            unit,
            tag,
            digest,
            mode,
            requested_by,
            context.now,
            verify=not no_verify,
        )
    context.say(
        f"{job.id} queued: {job.mode} {job.short_digest} on {job.board}",
        {"job": job.id, "board": job.board, "mode": job.mode},
    )


@jobs_group.command("list")
@click.option("--state", default=None, help="queued, running, passed, failed")
@click.option("--board", "tag", default=None, help="one board")
@click.pass_obj
@bench_command
def list_jobs(context: Context, state: str | None, tag: str | None) -> None:
    """What has been asked of the bench."""
    with context.unit() as unit:
        if tag:
            found = programming.for_board(unit, tag)
        else:
            found = tuple(
                job
                for board in unit.boards.all()
                for job in unit.jobs.for_board(board.tag)
                if state is None or job.state == state
            )
        rows = [
            {
                "job": job.id,
                "board": job.board,
                "mode": job.mode,
                "image": job.short_digest,
                "state": job.state,
                "attempts": job.attempts,
                "error": job.error or "-",
            }
            for job in found
        ]
    context.emit(rows, ("job", "board", "mode", "image", "state", "attempts", "error"))


@jobs_group.command("run")
@click.argument("job_id", required=False)
@click.option("--live", is_flag=True, help="use the real cable rather than the simulator")
@click.pass_obj
@bench_command
def run_job(context: Context, job_id: str | None, live: bool) -> None:
    """Run one job, the next ready one by default, and say what happened."""
    with context.unit() as unit:
        job = unit.jobs.get(job_id) if job_id else execution.next_ready(unit, context.now)
        if job is None:
            context.say("nothing is ready to run", {"ran": None})
            return
        result = execution.run_job(
            unit,
            job,
            context.now,
            settings=context.settings,
            live=live,
        )
    context.say(
        result.describe(),
        {
            "job": result.job.id,
            "state": result.job.state,
            "detail": result.detail,
            "board_faulted": result.faulted_board,
        },
    )


@jobs_group.command("cancel")
@click.argument("job_id")
@click.option("--reason", default="cancelled", help="why it is being dropped")
@click.pass_obj
@bench_command
def cancel(context: Context, job_id: str, reason: str) -> None:
    """Take a job off the queue."""
    with context.unit() as unit:
        cancelled = programming.cancel(unit, job_id, context.now, reason)
    context.say(
        f"{cancelled.id} cancelled",
        {"job": cancelled.id, "state": cancelled.state, "reason": reason},
    )
