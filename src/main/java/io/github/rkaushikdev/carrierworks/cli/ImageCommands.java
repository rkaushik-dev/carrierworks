package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Measure;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactStore;
import io.github.rkaushikdev.carrierworks.artifacts.PromotionDecision;
import io.github.rkaushikdev.carrierworks.artifacts.PromotionEvidence;
import io.github.rkaushikdev.carrierworks.artifacts.Provenance;
import io.github.rkaushikdev.carrierworks.bitstream.Binary;
import io.github.rkaushikdev.carrierworks.bitstream.Bitstream;
import io.github.rkaushikdev.carrierworks.bitstream.BuildStamp;
import io.github.rkaushikdev.carrierworks.bitstream.ConfigOptions;
import io.github.rkaushikdev.carrierworks.bitstream.Mcs;
import io.github.rkaushikdev.carrierworks.bitstream.PacketCrc;
import io.github.rkaushikdev.carrierworks.flash.FlashLayout;
import io.github.rkaushikdev.carrierworks.flash.FlashRegion;
import io.github.rkaushikdev.carrierworks.reports.BuildReport;
import io.github.rkaushikdev.carrierworks.reports.LoadCounts;
import io.github.rkaushikdev.carrierworks.services.Bundle;
import io.github.rkaushikdev.carrierworks.services.Bundles;
import io.github.rkaushikdev.carrierworks.services.Registry;
import io.github.rkaushikdev.carrierworks.services.Reports;
import io.github.rkaushikdev.carrierworks.util.Repr;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code fpga image}: taking images in, looking at them, and moving them up a channel.
 *
 * <p>The upload command is the one the build system calls, and it takes the two reports at the same
 * time because a build that produced an image produced them together. Uploading them separately is
 * allowed and is how somebody fixes a build that forgot, but the ordinary path is one call with
 * everything.
 */
@Command(
        name = "image",
        description = "The bitstreams the bench holds.",
        subcommands = {
            ImageCommands.Baseline.class,
            ImageCommands.RecordBuild.class,
            ImageCommands.MakeBundle.class,
            ImageCommands.CheckBundle.class,
            ImageCommands.Export.class,
            ImageCommands.ListImages.class,
            ImageCommands.Promote.class,
            ImageCommands.Regressions.class,
            ImageCommands.ShowImage.class,
            ImageCommands.Upload.class
        })
final class ImageCommands {

    private static final int SHORT_DIGEST = 12;

    /** Every channel, the one nearest the boards first. */
    private static final List<String> CHANNELS = List.of("golden", "qual", "dev");

    private ImageCommands() {}

    private static ArtifactStore store(Context context) {
        return new ArtifactStore(context.settings().artifactRoot());
    }

    /** The first twelve characters of a digest, or all of it when it is shorter. */
    private static String shortened(String digest) {
        return digest.length() <= SHORT_DIGEST ? digest : digest.substring(0, SHORT_DIGEST);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** Take a bit file in, with its reports if the build has them. */
    @Command(name = "upload", description = "Take a bit file in, with its reports if the build has them.")
    static final class Upload extends BenchCommand {

        @Parameters(paramLabel = "PATH", converter = FileArgument.Existing.class)
        private Path path;

        @Option(names = "--channel", paramLabel = "TEXT", description = "which channel it lands in")
        private String channel = ArtifactRecord.DEFAULT_CHANNEL;

        @Option(names = "--timing", paramLabel = "FILE", converter = FileArgument.Existing.class)
        private Optional<Path> timing = Optional.empty();

        @Option(names = "--utilization", paramLabel = "FILE", converter = FileArgument.Existing.class)
        private Optional<Path> utilization = Optional.empty();

        private record Stored(ArtifactRecord record, Optional<Boolean> closed) {}

        @Override
        int run(Context context) {
            Bitstream image = Bitstream.load(path);
            Instant now = context.now();
            Stored stored = context.apply(unit -> {
                ArtifactRecord record = Registry.ingest(unit, store(context), image, now, channel);
                if (timing.isEmpty() || utilization.isEmpty()) {
                    return new Stored(record, Optional.empty());
                }
                unit.flush();
                BuildReport report =
                        Reports.ingest(unit, record.digest(), now, read(timing.get()), read(utilization.get()));
                return new Stored(record, Optional.of(report.closed()));
            });
            ArtifactRecord record = stored.record();
            String message = record.design() + " " + record.shortDigest() + " stored in " + record.channel();
            if (stored.closed().isPresent()) {
                message = message + ", timing " + (stored.closed().get() ? "closed" : "did not close");
            }
            context.say(
                    message,
                    fields(
                            "digest", record.digest(),
                            "design", record.design(),
                            "channel", record.channel(),
                            "timing_closed", stored.closed()));
            return OK;
        }
    }

    /** The images the bench holds. */
    @Command(name = "list", description = "The images the bench holds.")
    static final class ListImages extends BenchCommand {

        @Option(names = "--design", paramLabel = "TEXT", description = "one design")
        private Optional<String> design = Optional.empty();

        @Option(names = "--channel", paramLabel = "TEXT", description = "one channel")
        private Optional<String> channel = Optional.empty();

        @Override
        int run(Context context) {
            Optional<String> oneDesign = design.filter(name -> !name.isEmpty());
            Optional<String> oneChannel = channel.filter(name -> !name.isEmpty());
            List<Map<String, Object>> rows = context.apply(unit -> {
                List<ArtifactRecord> found;
                if (oneDesign.isPresent()) {
                    found = unit.artifacts().forDesign(oneDesign.get());
                } else if (oneChannel.isPresent()) {
                    found = unit.artifacts().inChannel(oneChannel.get());
                } else {
                    found = CHANNELS.stream()
                            .flatMap(name -> unit.artifacts().inChannel(name).stream())
                            .toList();
                }
                return found.stream()
                        .map(record -> fields(
                                "digest", record.shortDigest(),
                                "design", record.design(),
                                "device", record.device(),
                                "kind", record.kind(),
                                "channel", record.channel(),
                                "bytes", record.length()))
                        .toList();
            });
            context.emit(rows, "digest", "design", "device", "kind", "channel", "bytes");
            return OK;
        }
    }

    /** One image, with whatever the bench knows about it. */
    @Command(name = "show", description = "One image, with whatever the bench knows about it.")
    static final class ShowImage extends BenchCommand {

        @Parameters(paramLabel = "DIGEST")
        private String digest;

        private record Known(
                ArtifactRecord record, Optional<BuildReport> report, List<Provenance> builds, LoadCounts loads) {

            Optional<Boolean> closed() {
                return report.map(BuildReport::closed);
            }

            List<String> buildIds() {
                return builds.stream().map(Provenance::buildId).toList();
            }
        }

        @Override
        int run(Context context) {
            Known known = context.apply(unit -> new Known(
                    unit.artifacts().get(digest),
                    unit.reports().forDigest(digest),
                    unit.provenance().forDigest(digest),
                    Registry.countedLoads(unit, digest)));
            ArtifactRecord record = known.record();
            byte[] data = store(context).read(record.digest());
            String options = ConfigOptions.of(data).describe();
            String crc = PacketCrc.check(data).describe();
            String stamped = BuildStamp.of(data).map(BuildStamp::describe).orElse("");
            if (context.asJson()) {
                context.say(
                        "",
                        fields(
                                "digest", record.digest(),
                                "design", record.design(),
                                "device", record.device(),
                                "channel", record.channel(),
                                "kind", record.kind(),
                                "bytes", record.length(),
                                "timing_closed", known.closed(),
                                "worst_slack_ps",
                                        known.report()
                                                .map(report -> report.timing().worstSlackPs()),
                                "builds", known.buildIds(),
                                "loads_passed", known.loads().passed(),
                                "loads_failed", known.loads().failed(),
                                "options", options,
                                "crc", crc,
                                "stamped", stamped));
                return OK;
            }
            List<String> builds = known.buildIds();
            context.echo(record.design() + " " + record.shortDigest() + " for " + record.device());
            Map<String, String> rows = new LinkedHashMap<>();
            rows.put("channel", record.channel() + " (" + record.kind() + ", " + record.length() + " bytes)");
            rows.put(
                    "timing",
                    known.closed().map(yes -> yes ? "closed" : "did not close").orElse("not read"));
            rows.put("builds", builds.isEmpty() ? "none recorded" : String.join(", ", builds));
            rows.put(
                    "loads",
                    known.loads().passed() + " passed, " + known.loads().failed() + " failed");
            rows.put("options", options);
            rows.put("crc", crc);
            rows.put("stamped", stamped.isEmpty() ? "not stamped" : stamped);
            rows.forEach((label, value) -> context.echo(String.format(Locale.ROOT, "%-8s %s", label, value)));
            return OK;
        }
    }

    /** Move an image up a channel, if the rules allow it. */
    @Command(name = "promote", description = "Move an image up a channel, if the rules allow it.")
    static final class Promote extends BenchCommand {

        @Parameters(index = "0", paramLabel = "DIGEST")
        private String digest;

        @Parameters(index = "1", paramLabel = "CHANNEL")
        private String channel;

        @Option(names = "--approve", paramLabel = "TEXT", description = "who is signing this off")
        private List<String> approvals = new ArrayList<>();

        @Override
        int run(Context context) {
            PromotionDecision decision = context.apply(unit -> {
                PromotionEvidence evidence = Registry.evidenceFrom(unit, digest, Optional.empty(), approvals);
                return Registry.promote(unit, digest, channel, evidence);
            });
            String moved = decision.toChannel().value();
            context.say(shortened(digest) + " is now in " + moved, fields("digest", digest, "channel", moved));
            return OK;
        }
    }

    /** How a build compares with the one its design is measured against. */
    @Command(name = "regressions", description = "How a build compares with the one its design is measured against.")
    static final class Regressions extends BenchCommand {

        @Parameters(paramLabel = "DIGEST")
        private String digest;

        @Override
        int run(Context context) {
            List<String> found = context.apply(unit -> Reports.regressions(unit, digest));
            if (found.isEmpty()) {
                context.say("nothing worse than the baseline", fields("digest", digest, "regressions", List.of()));
                return OK;
            }
            if (context.asJson()) {
                context.say("", fields("digest", digest, "regressions", found));
                return OK;
            }
            found.forEach(context::echo);
            return OK;
        }
    }

    /** Show or pin the build a design is measured against. */
    @Command(name = "baseline", description = "Show or pin the build a design is measured against.")
    static final class Baseline extends BenchCommand {

        @Parameters(paramLabel = "DESIGN")
        private String design;

        @Option(names = "--pin", paramLabel = "TEXT", description = "a digest to measure this design against")
        private Optional<String> pin = Optional.empty();

        @Override
        int run(Context context) {
            Optional<String> pinned = pin.filter(digest -> !digest.isEmpty());
            BuildReport report = context.apply(unit -> pinned.isPresent()
                    ? Reports.setBaseline(unit, pinned.get())
                    : Reports.baseline(unit, design).orElseThrow(() -> new NotFound("baseline", design)));
            context.say(
                    report.design() + " is measured against " + report.shortDigest(),
                    fields("design", report.design(), "digest", report.digest()));
            return OK;
        }
    }

    /**
     * Write out everything the bench knows about one image.
     *
     * <p>What comes out is a directory somebody can attach to a ticket: the configuration bytes, the
     * reports, where the image came from, and every load of it onto a board.
     */
    @Command(name = "bundle", description = "Write out everything the bench knows about one image.")
    static final class MakeBundle extends BenchCommand {

        @Parameters(paramLabel = "DIGEST")
        private String digest;

        @Option(
                names = "--into",
                paramLabel = "TEXT",
                required = true,
                description = "where to write the bundle directory")
        private String into;

        @Option(names = "--approved-by", paramLabel = "TEXT", description = "who signed it off, repeatable")
        private List<String> approvals = new ArrayList<>();

        @Override
        int run(Context context) {
            Bundle made = context.apply(
                    unit -> Bundles.assemble(unit, digest, Path.of(into), context.now(), store(context), approvals));
            context.say(
                    made.describe(),
                    fields(
                            "digest", made.digest(),
                            "root", made.root().toString(),
                            "files", made.files(),
                            "manifest_digest", made.manifestDigest(),
                            "jobs", made.jobs(),
                            "reports", made.reports(),
                            "builds", made.builds()));
            return OK;
        }
    }

    /**
     * Check a bundle against its own manifest.
     *
     * <p>Exits {@value BenchCommand#NEEDS_ATTENTION} when anything in the bundle does not match.
     */
    @Command(name = "check-bundle", description = "Check a bundle against its own manifest.")
    static final class CheckBundle extends BenchCommand {

        @Parameters(paramLabel = "PATH")
        private String path;

        @Override
        int run(Context context) {
            List<String> problems = Bundles.verify(Path.of(path));
            if (problems.isEmpty()) {
                context.say(path + " matches its manifest", fields("path", path, "problems", List.of()));
                return OK;
            }
            context.say(
                    path + " has " + problems.size() + " problems: " + String.join("; ", problems),
                    fields("path", path, "problems", problems));
            return NEEDS_ATTENTION;
        }
    }

    /**
     * Write an image out for a programmer that is not this bench.
     *
     * <p>Two things about the bytes are worth saying out loud, because both have cost somebody a day.
     * An mcs file carries the address it loads at, so an image meant for the update half of the part
     * has to be exported with that address or it lands on top of the golden image. And a part
     * programmed over spi wants each byte bit reversed, which is not something the file records: it
     * is a property of how the programmer clocks it out.
     */
    @Command(name = "export", description = "Write an image out for a programmer that is not this bench.")
    static final class Export extends BenchCommand {

        @Parameters(paramLabel = "DIGEST")
        private String digest;

        @Option(names = "--into", paramLabel = "FILE", required = true, converter = FileArgument.Target.class)
        private Path into;

        @Option(names = "--format", paramLabel = "TEXT", description = "bin or mcs")
        private String shape = "bin";

        @Option(
                names = "--at",
                paramLabel = "TEXT",
                description = "the flash address it will be written to, a size such as 8MiB, or a region")
        private String start = "0";

        @Option(
                names = "--pad-to",
                paramLabel = "TEXT",
                description = "pad out to this many bytes with erased flash, or a size such as 4MiB")
        private String pad = "0";

        @Option(names = "--board", paramLabel = "TEXT", description = "the board whose flash layout --at names")
        private Optional<String> board = Optional.empty();

        @Option(names = "--spi", description = "bit reverse each byte for a spi part")
        private boolean spi;

        @Override
        int run(Context context) {
            if (!shape.equals("bin") && !shape.equals("mcs")) {
                throw new UsageError("the format is bin or mcs");
            }
            Optional<FlashLayout> layout = board.map(tag -> FlashLayout.halves(
                    context.apply(unit -> unit.boards().get(tag)).flashSpec()));
            long address = address(layout);
            if (address < 0) {
                throw new UsageError("a flash address is not negative");
            }
            ArtifactStore store = store(context);
            ArtifactRecord record = context.apply(unit -> unit.artifacts().get(digest));
            byte[] data = store.read(record.digest());
            long padTo = padding();
            if (padTo != 0) {
                data = Binary.padTo(data, padTo);
            }
            Optional<FlashRegion> landing = layout.flatMap(found -> found.regionAt(address));
            if (landing.isPresent() && address + data.length > landing.get().end()) {
                throw new ValidationError(
                        "the image runs past the end of the " + landing.get().name() + " region",
                        "at",
                        Details.of("wanted", data.length, "room", landing.get().end() - address));
            }
            if (spi) {
                data = Binary.reverseBits(data);
            }
            try {
                if (shape.equals("mcs")) {
                    Files.writeString(into, Mcs.write(data, address), StandardCharsets.UTF_8);
                } else {
                    Files.write(into, data);
                }
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            context.say(
                    record.shortDigest() + " written to " + into + " as " + shape + ", " + data.length + " bytes"
                            + landing.map(region -> ", for the " + region.name() + " region")
                                    .orElse(""),
                    fields(
                            "digest",
                            record.digest(),
                            "path",
                            into.toString(),
                            "format",
                            shape,
                            "bytes",
                            data.length,
                            "address",
                            address,
                            "region",
                            landing.map(FlashRegion::name).orElse(""),
                            "spi",
                            spi));
            return OK;
        }

        /**
         * The address as typed, or the start of the named region on the board's flash.
         *
         * @throws UsageError when it is neither, or a region is named with no board to find it on
         */
        private long address(Optional<FlashLayout> layout) {
            String text = start.strip();
            if (!text.isEmpty() && text.chars().allMatch(Character::isLetter)) {
                return layout.orElseThrow(() -> new UsageError("give --board to export to a named region"))
                        .region(text)
                        .start();
            }
            try {
                return Measure.parseAddress(start);
            } catch (ValidationError unreadable) {
                throw new UsageError("a flash address is a whole number, a size or a region, not " + Repr.of(start));
            }
        }

        private long padding() {
            try {
                return Measure.parseAddress(pad);
            } catch (ValidationError unreadable) {
                throw new UsageError("padding is a whole number of bytes or a size, not " + Repr.of(pad));
            }
        }
    }

    /**
     * Write down the build that produced an image.
     *
     * <p>Until this is recorded the bench knows an image exists and nothing about where it came from,
     * which is why an image with no build behind it cannot be promoted to golden however well it runs.
     * Two builds of the same source that produce the same bytes are both recorded, and disagreeing
     * revisions for one image are what the promotion gate looks at.
     */
    @Command(name = "build", description = "Write down the build that produced an image.")
    static final class RecordBuild extends BenchCommand {

        @Parameters(paramLabel = "DIGEST")
        private String digest;

        @Option(
                names = "--revision",
                paramLabel = "TEXT",
                required = true,
                description = "the git object the image was built from")
        private String revision;

        @Option(
                names = "--build-id",
                paramLabel = "TEXT",
                required = true,
                description = "what the build system calls this run")
        private String buildId;

        @Option(names = "--branch", paramLabel = "TEXT", description = "the branch it was built on")
        private String branch = "";

        @Option(names = "--builder", paramLabel = "TEXT", description = "which machine built it")
        private String builder = "";

        @Option(names = "--tool", paramLabel = "TEXT", description = "the toolchain version")
        private String toolVersion = "";

        @Option(names = "--constraints", paramLabel = "TEXT", description = "hash of the xdc files")
        private String constraintsDigest = "";

        @Option(names = "--dirty", description = "the working tree had uncommitted changes")
        private boolean dirty;

        private record Recorded(ArtifactRecord record, Provenance build, int builds) {}

        @Override
        int run(Context context) {
            Instant now = context.now();
            Recorded recorded = context.apply(unit -> {
                ArtifactRecord record = unit.artifacts().get(digest);
                Provenance build = Registry.recordBuild(
                        unit,
                        new Provenance.Builder(
                                        record.digest(),
                                        record.design(),
                                        revision,
                                        buildId,
                                        Times.iso(now, Times.Precision.SECONDS))
                                .toolVersion(toolVersion)
                                .constraintsDigest(constraintsDigest)
                                .builder(builder)
                                .branch(branch)
                                .dirty(dirty)
                                .build());
                return new Recorded(
                        record,
                        build,
                        unit.provenance().forDigest(record.digest()).size());
            });
            Provenance build = recorded.build();
            context.say(
                    recorded.record().shortDigest() + " built from " + build.shortRevision() + " as " + build.buildId(),
                    fields(
                            "digest", build.digest(),
                            "revision", build.sourceRevision(),
                            "build_id", build.buildId(),
                            "branch", build.branch(),
                            "dirty", build.dirty(),
                            "builds", recorded.builds()));
            return OK;
        }
    }
}
