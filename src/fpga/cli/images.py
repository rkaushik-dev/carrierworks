"""Images: taking them in, looking at them, and moving them up a channel.

The upload command is the one the build system calls, and it takes the two
reports at the same time because a build that produced an image produced them
together. Uploading them separately is allowed and is how somebody fixes a
build that forgot, but the ordinary path is one call with everything.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

import click

from fpga.artifacts.registry import ArtifactStore
from fpga.bitstream.image import load_bitstream
from fpga.cli.main import Context, bench_command, cli
from fpga.errors import NotFound
from fpga.services import registry, reports

__all__ = ["images_group"]


def _store(context: Context) -> ArtifactStore:
    return ArtifactStore(context.settings.artifact_root)


@cli.group("image")
def images_group() -> None:
    """The bitstreams the bench holds."""


@images_group.command("upload")
@click.argument("path", type=click.Path(exists=True, dir_okay=False, path_type=Path))
@click.option("--channel", default="dev", help="which channel it lands in")
@click.option("--timing", type=click.Path(exists=True, dir_okay=False, path_type=Path))
@click.option("--utilization", type=click.Path(exists=True, dir_okay=False, path_type=Path))
@click.pass_obj
@bench_command
def upload(
    context: Context,
    path: Path,
    channel: str,
    timing: Path | None,
    utilization: Path | None,
) -> None:
    """Take a bit file in, with its reports if the build has them."""
    image = load_bitstream(path)
    with context.unit() as unit:
        record = registry.ingest(unit, _store(context), image, context.now, channel=channel)
        closed = None
        if timing and utilization:
            unit.flush()
            report = reports.ingest(
                unit,
                record.digest,
                context.now,
                timing_text=timing.read_text(encoding="utf-8"),
                utilization_text=utilization.read_text(encoding="utf-8"),
            )
            closed = report.closed
    message = f"{record.design} {record.short_digest} stored in {record.channel}"
    if closed is not None:
        message = f"{message}, timing {'closed' if closed else 'did not close'}"
    context.say(
        message,
        {
            "digest": record.digest,
            "design": record.design,
            "channel": record.channel,
            "timing_closed": closed,
        },
    )


@images_group.command("list")
@click.option("--design", default=None, help="one design")
@click.option("--channel", default=None, help="one channel")
@click.pass_obj
@bench_command
def list_images(context: Context, design: str | None, channel: str | None) -> None:
    """The images the bench holds."""
    with context.unit() as unit:
        if design:
            found = unit.artifacts.for_design(design)
        elif channel:
            found = unit.artifacts.in_channel(channel)
        else:
            found = tuple(
                record
                for name in ("golden", "qual", "dev")
                for record in unit.artifacts.in_channel(name)
            )
        rows = [
            {
                "digest": record.short_digest,
                "design": record.design,
                "device": record.device,
                "kind": record.kind,
                "channel": record.channel,
                "bytes": record.length,
            }
            for record in found
        ]
    context.emit(rows, ("digest", "design", "device", "kind", "channel", "bytes"))


@images_group.command("show")
@click.argument("digest")
@click.pass_obj
@bench_command
def show_image(context: Context, digest: str) -> None:
    """One image, with whatever the bench knows about it."""
    with context.unit() as unit:
        record = unit.artifacts.get(digest)
        report = unit.reports.for_digest(digest)
        builds = unit.provenance.for_digest(digest)
        passed, failed = registry.counted_loads(unit, digest)
        payload: dict[str, Any] = {
            "digest": record.digest,
            "design": record.design,
            "device": record.device,
            "channel": record.channel,
            "kind": record.kind,
            "bytes": record.length,
            "timing_closed": None if report is None else report.closed,
            "worst_slack_ps": None if report is None else report.timing.worst_slack_ps,
            "builds": [build.build_id for build in builds],
            "loads_passed": passed,
            "loads_failed": failed,
        }
    if context.as_json:
        context.say("", payload)
        return
    click.echo(f"{payload['design']} {record.short_digest} for {payload['device']}")
    click.echo(f"channel  {payload['channel']} ({payload['kind']}, {payload['bytes']} bytes)")
    timing = payload["timing_closed"]
    click.echo(
        "timing   " + ("not read" if timing is None else "closed" if timing else "did not close")
    )
    built = ", ".join(build.build_id for build in builds) or "none recorded"
    click.echo(f"builds   {built}")
    click.echo(f"loads    {passed} passed, {failed} failed")


@images_group.command("promote")
@click.argument("digest")
@click.argument("channel")
@click.option("--approve", "approvals", multiple=True, help="who is signing this off")
@click.pass_obj
@bench_command
def promote(context: Context, digest: str, channel: str, approvals: tuple[str, ...]) -> None:
    """Move an image up a channel, if the rules allow it."""
    with context.unit() as unit:
        evidence = registry.evidence_from(unit, digest, approvals=approvals)
        decision = registry.promote(unit, digest, channel, evidence)
    context.say(
        f"{digest[:12]} is now in {decision.to_channel}",
        {"digest": digest, "channel": decision.to_channel},
    )


@images_group.command("regressions")
@click.argument("digest")
@click.pass_obj
@bench_command
def regressions(context: Context, digest: str) -> None:
    """How a build compares with the one its design is measured against."""
    with context.unit() as unit:
        found = reports.regressions(unit, digest)
    if not found:
        context.say("nothing worse than the baseline", {"digest": digest, "regressions": []})
        return
    if context.as_json:
        context.say("", {"digest": digest, "regressions": list(found)})
        return
    for line in found:
        click.echo(line)


@images_group.command("baseline")
@click.argument("design")
@click.option("--pin", default=None, help="a digest to measure this design against")
@click.pass_obj
@bench_command
def baseline(context: Context, design: str, pin: str | None) -> None:
    """Show or pin the build a design is measured against."""
    with context.unit() as unit:
        if pin:
            report = reports.set_baseline(unit, pin)
        else:
            found = reports.baseline(unit, design)
            if found is None:
                raise NotFound("baseline", design)
            report = found
    context.say(
        f"{report.design} is measured against {report.short_digest}",
        {"design": report.design, "digest": report.digest},
    )
