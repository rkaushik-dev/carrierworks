package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.Measure;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactStore;
import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import io.github.rkaushikdev.carrierworks.flash.EraseUnit;
import io.github.rkaushikdev.carrierworks.flash.FlashLayout;
import io.github.rkaushikdev.carrierworks.flash.FlashPlan;
import io.github.rkaushikdev.carrierworks.flash.FlashRegion;
import io.github.rkaushikdev.carrierworks.flash.WearLedger;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.util.Numbers;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code fpga flash}: the flash part on a board, what is where, what a write would cost, and wear.
 *
 * <p>Three questions come up in front of a board with a stuck image, and none of them needs the
 * board powered. Where does the golden image live on this part. What would writing this image
 * actually do to it, step by step. And how many erases has this part taken, because the sector at
 * address zero has taken every one of them.
 *
 * <p>The plan command is the useful one. A flash write is minutes long and mostly erase, and the
 * plan says how many minutes before anybody commits to it, so a job that was going to blow through a
 * maintenance window can be moved instead of interrupted.
 */
@Command(
        name = "flash",
        description = "The configuration part, its layout and its wear.",
        subcommands = {FlashCommands.ShowLayout.class, FlashCommands.ShowPlan.class, FlashCommands.ShowWear.class})
final class FlashCommands {

    private FlashCommands() {}

    private static String hex(long address) {
        return String.format(Locale.ROOT, "0x%08x", address);
    }

    private static Board board(Context context, String tag) {
        return context.apply(unit -> unit.boards().get(tag));
    }

    /** Where the golden and update images live on this board's part. */
    @Command(name = "layout", description = "Where the golden and update images live on this board's part.")
    static final class ShowLayout extends BenchCommand {

        @Parameters(paramLabel = "TAG")
        private String tag;

        @Override
        int run(Context context) {
            FlashLayout layout = FlashLayout.halves(board(context, tag).flashSpec());
            List<Map<String, Object>> rows = layout.regions().stream()
                    .sorted(Comparator.comparingLong(FlashRegion::start))
                    .map(region -> fields(
                            "region", region.name(),
                            "start", hex(region.start()),
                            "end", hex(region.end()),
                            "size", Measure.formatSize(region.length()),
                            "sectors", layout.sectorsFor(region.name())))
                    .toList();
            context.emit(rows, "region", "start", "end", "size", "sectors");
            return OK;
        }
    }

    /** Every step a write would take, and what it would cost in time. */
    @Command(name = "plan", description = "Every step a write would take, and what it would cost in time.")
    static final class ShowPlan extends BenchCommand {

        @Parameters(paramLabel = "TAG")
        private String tag;

        @Option(names = "--image", paramLabel = "TEXT", description = "the image to cost a write of")
        private Optional<String> digest = Optional.empty();

        @Option(names = "--region", paramLabel = "TEXT", description = "golden or update")
        private String region = "update";

        @Option(names = "--erase", description = "cost clearing the region instead")
        private boolean eraseOnly;

        @Option(names = "--no-verify", description = "leave the read back out")
        private boolean noVerify;

        @Option(names = "--subsectors", description = "erase in subsectors where the part has them")
        private boolean subsectors;

        @Option(names = "--fitted", description = "whole sectors where quicker, subsectors at the ragged ends")
        private boolean fitted;

        @Override
        int run(Context context) {
            FlashLayout layout = FlashLayout.halves(board(context, tag).flashSpec());
            if (subsectors && fitted) {
                throw new UsageError("erase in subsectors or fitted, not both");
            }
            EraseUnit erase = fitted ? EraseUnit.FITTED : subsectors ? EraseUnit.SUBSECTOR : EraseUnit.SECTOR;
            FlashPlan plan;
            if (eraseOnly) {
                plan = FlashPlan.forErase(layout, region, erase);
            } else {
                if (digest.isEmpty()) {
                    throw new UsageError("give an image to write, or ask for an erase");
                }
                byte[] image = new ArtifactStore(context.settings().artifactRoot()).read(digest.get());
                plan = FlashPlan.forWrite(layout, region, image, erase, !noVerify);
            }
            if (context.asJson()) {
                context.emit(
                        plan.steps().stream()
                                .map(step -> fields(
                                        "kind", step.kind().value(),
                                        "address", hex(step.address()),
                                        "length", step.length(),
                                        "ms", step.estimatedMs()))
                                .toList(),
                        "kind",
                        "address",
                        "length",
                        "ms");
                return OK;
            }
            context.echo(plan.describe());
            context.echo("erased   " + Measure.formatSize(plan.erasedBytes()));
            context.echo("written  " + Measure.formatSize(plan.programmedBytes()));
            context.echo("verify   " + (plan.verifies() ? "yes" : "no"));
            context.echo("about    " + plan.totalSeconds() + "s");
            return OK;
        }
    }

    /** Erase counts, per sector for one board or per board for the bench. */
    @Command(name = "wear", description = "Erase counts, per sector for one board or per board for the bench.")
    static final class ShowWear extends BenchCommand {

        @Parameters(paramLabel = "TAG", arity = "0..1")
        private Optional<String> tag = Optional.empty();

        @Option(names = "--top", paramLabel = "INTEGER", description = "how many sectors to show for one board")
        private int top = 5;

        @Override
        int run(Context context) {
            WearLedger ledger = new WearLedger(context.settings().wearLedger());
            if (tag.isEmpty()) {
                List<Map<String, Object>> rows = context.apply(unit -> unit.boards().all().stream()
                        .map(board -> fields(
                                "asset", board.tag(),
                                "part", board.flash(),
                                "erases", ledger.total(board.tag()),
                                "worn", ledger.isWorn(board.tag(), board.flashSpec()) ? "yes" : "no"))
                        .toList());
                context.emit(rows, "asset", "part", "erases", "worn");
                return OK;
            }
            Board board = board(context, tag.get());
            FlashSpec spec = board.flashSpec();
            List<Map<String, Object>> sectors = ledger.hottest(board.tag(), spec, top).stream()
                    .map(entry -> fields(
                            "sector", entry.sector(),
                            "erases", entry.erases(),
                            "rated", entry.rated(),
                            "used", Numbers.fixed(entry.fraction() * 100, 2) + "%",
                            "worn", entry.worn() ? "yes" : "no"))
                    .toList();
            context.emit(sectors, "sector", "erases", "rated", "used", "worn");
            return OK;
        }
    }
}
