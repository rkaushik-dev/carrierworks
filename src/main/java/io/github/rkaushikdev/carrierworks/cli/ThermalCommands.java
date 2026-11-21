package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.Temperature;
import io.github.rkaushikdev.carrierworks.services.Thermal;
import io.github.rkaushikdev.carrierworks.thermal.SupervisorAction;
import io.github.rkaushikdev.carrierworks.thermal.ThermalSample;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code fpga thermal}: temperatures from the command line, fed in, read back and acted on.
 *
 * <p>The record command is the one that gets called from somewhere else. The poller on each rack
 * controller reads its own boards and shells out to this, because that was a two line change on the
 * controllers and a new daemon was not.
 *
 * <p>The tick command is the whole supervisor pass and it does act: a board over the shutdown line
 * loses its power, its lease and its place in service. Nothing else here changes anything, so status
 * is safe to run in a loop while somebody watches a board warm up.
 */
@Command(
        name = "thermal",
        description = "Readings, alarm states and the supervisor pass.",
        subcommands = {
            ThermalCommands.History.class,
            ThermalCommands.Prune.class,
            ThermalCommands.Record.class,
            ThermalCommands.Status.class,
            ThermalCommands.Tick.class
        })
final class ThermalCommands {

    private ThermalCommands() {}

    /** Store one reading, in celsius or millidegrees. */
    @Command(name = "record", description = "Store one reading, in celsius or millidegrees.")
    static final class Record extends BenchCommand {

        @Parameters(index = "0", paramLabel = "TAG")
        private String tag;

        @Parameters(index = "1", paramLabel = "TEMPERATURE")
        private String temperature;

        @Option(names = "--source", paramLabel = "TEXT", description = "which sensor this came off")
        private String source = ThermalSample.DEFAULT_SOURCE;

        @Override
        int run(Context context) {
            int millidegrees = Temperature.parse(temperature);
            ThermalSample sample =
                    context.apply(unit -> Thermal.record(unit, tag, context.now(), millidegrees, source));
            context.say(
                    sample.describe(),
                    fields(
                            "board", sample.board(),
                            "at", sample.at(),
                            "millidegrees", sample.millidegrees(),
                            "source", sample.source()));
            return OK;
        }
    }

    /** Where every board stands, worst first, changing nothing. */
    @Command(name = "status", description = "Where every board stands, worst first, changing nothing.")
    static final class Status extends BenchCommand {

        @Option(names = "--window", paramLabel = "INTEGER", description = "how many minutes of readings to look at")
        private int window = 15;

        @Option(names = "--alarming", description = "only the boards worth waking somebody for")
        private boolean alarming;

        @Override
        int run(Context context) {
            Instant now = context.now();
            List<Map<String, Object>> rows = context.apply(
                    unit -> Thermal.statusOf(unit, now, context.settings(), Duration.ofMinutes(window)).stream()
                            .filter(entry -> !alarming || entry.alarming())
                            .map(entry -> fields(
                                    "asset", entry.board(),
                                    "state", entry.state().value(),
                                    "reading",
                                            entry.millidegrees().isPresent()
                                                    ? Temperature.render(
                                                            entry.millidegrees().getAsInt())
                                                    : "-",
                                    "for", Durations.clock(entry.heldFor(now)),
                                    "stale", entry.stale() ? "yes" : "no"))
                            .toList());
            context.emit(rows, "asset", "state", "reading", "for", "stale");
            return OK;
        }
    }

    /** Every reading for one board, oldest first. */
    @Command(name = "history", description = "Every reading for one board, oldest first.")
    static final class History extends BenchCommand {

        @Parameters(paramLabel = "TAG")
        private String tag;

        @Option(names = "--window", paramLabel = "INTEGER", description = "how many minutes back to go")
        private int window = 60;

        @Override
        int run(Context context) {
            Instant now = context.now();
            List<Map<String, Object>> rows = context.apply(unit -> {
                unit.boards().get(tag);
                return unit.thermal().since(now.minus(Duration.ofMinutes(window)), tag).stream()
                        .map(sample -> fields(
                                "at", Times.iso(sample.at(), Times.Precision.SECONDS),
                                "reading", Temperature.render(sample.millidegrees()),
                                "source", sample.source()))
                        .toList();
            });
            context.emit(rows, "at", "reading", "source");
            return OK;
        }
    }

    /** One supervisor pass, which will shut a board down if it has to. */
    @Command(name = "tick", description = "One supervisor pass, which will shut a board down if it has to.")
    static final class Tick extends BenchCommand {

        @Option(names = "--window", paramLabel = "INTEGER", description = "how many minutes of readings to look at")
        private int window = 15;

        @Override
        int run(Context context) {
            List<SupervisorAction> actions = context.apply(
                    unit -> Thermal.tick(unit, context.now(), context.settings(), Duration.ofMinutes(window)));
            if (actions.isEmpty()) {
                context.say("nothing to do", fields("actions", List.of()));
                return OK;
            }
            List<Map<String, Object>> rows = actions.stream()
                    .map(action -> fields(
                            "asset", action.board(),
                            "action", action.action().value(),
                            "reason", action.reason(),
                            "downwind", action.affected().isEmpty() ? "-" : String.join(",", action.affected())))
                    .toList();
            context.emit(rows, "asset", "action", "reason", "downwind");
            return OK;
        }
    }

    /** Drop readings older than the retention window. */
    @Command(name = "prune", description = "Drop readings older than the retention window.")
    static final class Prune extends BenchCommand {

        @Option(names = "--keep-days", paramLabel = "INTEGER", description = "how much history to keep")
        private int keepDays = 7;

        @Override
        int run(Context context) {
            int dropped = context.apply(unit -> Thermal.prune(unit, context.now(), Duration.ofDays(keepDays)));
            context.say(
                    "dropped " + dropped + " readings older than " + keepDays + " days",
                    fields("dropped", dropped, "keep_days", keepDays));
            return OK;
        }
    }
}
