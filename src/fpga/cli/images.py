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
from fpga.bitstream.binary import pad_to, reverse_bits
from fpga.bitstream.image import load_bitstream
from fpga.bitstream.mcs import write_mcs
from fpga.cli.main import Context, bench_command, cli
from fpga.errors import NotFound
from fpga.services import bundles, registry, reports

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


@images_group.command("bundle")
@click.argument("digest")
@click.option("--into", required=True, help="where to write the bundle directory")
@click.option("--approved-by", "approvals", multiple=True, help="who signed it off, repeatable")
@click.pass_obj
@bench_command
def bundle(context: Context, digest: str, into: str, approvals: tuple[str, ...]) -> None:
    """Write out everything the bench knows about one image.

    What comes out is a directory somebody can attach to a ticket: the
    configuration bytes, the reports, where the image came from, and every
    load of it onto a board.
    """
    with context.unit() as unit:
        made = bundles.assemble(
            unit,
            digest,
            into,
            context.now,
            store=_store(context),
            approvals=tuple(approvals),
        )
    context.say(
        made.describe(),
        {
            "digest": made.digest,
            "root": str(made.root),
            "files": list(made.files),
            "manifest_digest": made.manifest_digest,
            "jobs": made.jobs,
            "reports": made.reports,
            "builds": made.builds,
        },
    )


@images_group.command("check-bundle")
@click.argument("path")
@click.pass_obj
@bench_command
def check_bundle(context: Context, path: str) -> None:
    """Check a bundle against its own manifest."""
    problems = bundles.verify(path)
    if not problems:
        context.say(f"{path} matches its manifest", {"path": path, "problems": []})
        return
    context.say(
        f"{path} has {len(problems)} problems: " + "; ".join(problems),
        {"path": path, "problems": list(problems)},
    )
    raise SystemExit(2)


@images_group.command("export")
@click.argument("digest")
@click.option("--into", required=True, type=click.Path(dir_okay=False, path_type=Path))
@click.option("--format", "shape", default="bin", help="bin or mcs")
@click.option("--at", "start", default="0", help="the flash address it will be written to")
@click.option("--pad-to", "pad", default=0, help="pad out to this many bytes with erased flash")
@click.option("--spi", is_flag=True, help="bit reverse each byte for a spi part")
@click.pass_obj
@bench_command
def export(
    context: Context,
    digest: str,
    into: Path,
    shape: str,
    start: str,
    pad: int,
    spi: bool,
) -> None:
    """Write an image out for a programmer that is not this bench.

    Two things about the bytes are worth saying out loud, because both have
    cost somebody a day. An mcs file carries the address it loads at, so an
    image meant for the update half of the part has to be exported with that
    address or it lands on top of the golden image. And a part programmed over
    spi wants each byte bit reversed, which is not something the file records:
    it is a property of how the programmer clocks it out.
    """
    if shape not in ("bin", "mcs"):
        raise click.UsageError("the format is bin or mcs")
    address = int(start, 0)
    if address < 0:
        raise click.UsageError("a flash address is not negative")
    store = _store(context)
    with context.unit() as unit:
        record = unit.artifacts.get(digest)
    data = store.read(record.digest)
    if pad:
        data = pad_to(data, pad)
    if spi:
        data = reverse_bits(data)
    if shape == "mcs":
        into.write_text(write_mcs(data, start_address=address), encoding="utf-8")
    else:
        into.write_bytes(data)
    context.say(
        f"{record.short_digest} written to {into} as {shape}, {len(data)} bytes",
        {
            "digest": record.digest,
            "path": str(into),
            "format": shape,
            "bytes": len(data),
            "address": address,
            "spi": spi,
        },
    )
