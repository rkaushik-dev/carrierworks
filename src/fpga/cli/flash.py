"""The flash part on a board: what is where, what a write would cost, and wear.

Three questions come up in front of a board with a stuck image, and none of
them needs the board powered. Where does the golden image live on this part.
What would writing this image actually do to it, step by step. And how many
erases has this part taken, because the sector at address zero has taken every
one of them.

The plan command is the useful one. A flash write is minutes long and mostly
erase, and the plan says how many minutes before anybody commits to it, so a
job that was going to blow through a maintenance window can be moved instead of
interrupted.
"""

from __future__ import annotations

import click

from fpga.artifacts.registry import ArtifactStore
from fpga.cli.main import Context, bench_command, cli
from fpga.flash.plan import plan_erase, plan_write
from fpga.flash.wear import WearLedger
from fpga.jobs.runner import flash_layout
from fpga.measure import format_size

__all__ = ["flash_group"]


@cli.group("flash")
def flash_group() -> None:
    """The configuration part, its layout and its wear."""


@flash_group.command("layout")
@click.argument("tag")
@click.pass_obj
@bench_command
def show_layout(context: Context, tag: str) -> None:
    """Where the golden and update images live on this board's part."""
    with context.unit() as unit:
        board = unit.boards.get(tag)
    layout = flash_layout(board.flash_spec())
    rows = [
        {
            "region": region.name,
            "start": f"0x{region.start:08x}",
            "end": f"0x{region.end:08x}",
            "size": format_size(region.length),
            "sectors": layout.sectors_for(region.name),
        }
        for region in sorted(layout.regions, key=lambda entry: entry.start)
    ]
    context.emit(rows, ("region", "start", "end", "size", "sectors"))


@flash_group.command("plan")
@click.argument("tag")
@click.option("--image", "digest", default=None, help="the image to cost a write of")
@click.option("--region", default="update", help="golden or update")
@click.option("--erase", "erase_only", is_flag=True, help="cost clearing the region instead")
@click.option("--no-verify", is_flag=True, help="leave the read back out")
@click.option("--subsectors", is_flag=True, help="erase in subsectors where the part has them")
@click.pass_obj
@bench_command
def show_plan(
    context: Context,
    tag: str,
    digest: str | None,
    region: str,
    erase_only: bool,
    no_verify: bool,
    subsectors: bool,
) -> None:
    """Every step a write would take, and what it would cost in time."""
    with context.unit() as unit:
        board = unit.boards.get(tag)
    layout = flash_layout(board.flash_spec())
    if erase_only:
        plan = plan_erase(layout, region, use_subsectors=subsectors)
    else:
        if digest is None:
            raise click.UsageError("give an image to write, or ask for an erase")
        image = ArtifactStore(context.settings.artifact_root).read(digest)
        plan = plan_write(
            layout,
            region,
            image,
            verify=not no_verify,
            use_subsectors=subsectors,
        )
    if context.as_json:
        context.emit(
            [
                {
                    "kind": step.kind,
                    "address": f"0x{step.address:08x}",
                    "length": step.length,
                    "ms": step.estimated_ms,
                }
                for step in plan.steps
            ],
            ("kind", "address", "length", "ms"),
        )
        return
    click.echo(plan.describe())
    click.echo(f"erased   {format_size(plan.erased_bytes)}")
    click.echo(f"written  {format_size(plan.programmed_bytes)}")
    click.echo(f"verify   {'yes' if plan.verifies else 'no'}")
    click.echo(f"about    {plan.total_seconds}s")


@flash_group.command("wear")
@click.argument("tag", required=False)
@click.option("--top", default=5, help="how many sectors to show for one board")
@click.pass_obj
@bench_command
def show_wear(context: Context, tag: str | None, top: int) -> None:
    """Erase counts, per sector for one board or per board for the bench."""
    ledger = WearLedger(context.settings.wear_ledger)
    with context.unit() as unit:
        if tag is None:
            rows = [
                {
                    "asset": board.tag,
                    "part": board.flash,
                    "erases": ledger.total(board.tag),
                    "worn": "yes" if ledger.is_worn(board.tag, board.flash_spec()) else "no",
                }
                for board in unit.boards.all()
            ]
            context.emit(rows, ("asset", "part", "erases", "worn"))
            return
        board = unit.boards.get(tag)
    spec = board.flash_spec()
    sectors = [
        {
            "sector": entry.sector,
            "erases": entry.erases,
            "rated": entry.rated,
            "used": f"{entry.fraction * 100:.2f}%",
            "worn": "yes" if entry.worn else "no",
        }
        for entry in ledger.hottest(board.tag, spec, top)
    ]
    context.emit(sectors, ("sector", "erases", "rated", "used", "worn"))
