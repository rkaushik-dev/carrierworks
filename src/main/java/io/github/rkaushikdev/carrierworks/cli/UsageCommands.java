package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.services.Usage;
import io.github.rkaushikdev.carrierworks.services.UsageReport;
import io.github.rkaushikdev.carrierworks.util.Numbers;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code fpga usage}: the usage report, which is what gets printed before a budget meeting.
 *
 * <p>Three views over the same window, because the three questions are different. Boards answers
 * whether the hardware is being used. Teams answers who is using it. Idle answers which boards to
 * move, lend out or stop paying maintenance on.
 */
@Command(
        name = "usage",
        description = "Who used what, and for how long.",
        subcommands = {
            UsageCommands.Boards.class,
            UsageCommands.Idle.class,
            UsageCommands.Summary.class,
            UsageCommands.Teams.class
        })
final class UsageCommands {

    /** Most first, and by name where two are level. */
    private static final Comparator<Map.Entry<String, Double>> MOST_FIRST =
            Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
                    .reversed()
                    .thenComparing(Map.Entry::getKey);

    private UsageCommands() {}

    private static UsageReport report(Context context, int days) {
        return context.apply(unit -> Usage.usage(unit, context.now(), Duration.ofDays(days)));
    }

    /** How much of the window each board spent leased. */
    @Command(name = "boards", description = "How much of the window each board spent leased.")
    static final class Boards extends BenchCommand {

        @Option(names = "--days", paramLabel = "INTEGER", description = "how far back to look")
        private int days = 7;

        @Override
        int run(Context context) {
            UsageReport report = report(context, days);
            List<Map<String, Object>> rows = report.utilisation().entrySet().stream()
                    .sorted(MOST_FIRST)
                    .map(entry -> fields(
                            "asset", entry.getKey(),
                            "used", Numbers.fixed(entry.getValue() * 100, 1) + "%",
                            "hours", Numbers.round(entry.getValue() * report.hours(), 1)))
                    .toList();
            context.emit(rows, "asset", "used", "hours");
            return OK;
        }
    }

    /** Board hours by team, most first. */
    @Command(name = "teams", description = "Board hours by team, most first.")
    static final class Teams extends BenchCommand {

        @Option(names = "--days", paramLabel = "INTEGER", description = "how far back to look")
        private int days = 7;

        @Override
        int run(Context context) {
            List<Map<String, Object>> rows = report(context, days).holderHours().entrySet().stream()
                    .sorted(MOST_FIRST)
                    .map(entry -> fields("holder", entry.getKey(), "hours", entry.getValue()))
                    .toList();
            context.emit(rows, "holder", "hours");
            return OK;
        }
    }

    /** Boards nobody leased at all in the window. */
    @Command(name = "idle", description = "Boards nobody leased at all in the window.")
    static final class Idle extends BenchCommand {

        @Option(names = "--days", paramLabel = "INTEGER", description = "how far back to look")
        private int days = 30;

        @Override
        int run(Context context) {
            UsageReport report = report(context, days);
            String idle = report.idle().isEmpty() ? "none" : String.join(", ", report.idle());
            context.say(
                    report.idle().size() + " of " + report.boards().size() + " boards idle for " + days + " days: "
                            + idle,
                    fields("days", days, "idle", report.idle(), "boards", report.boards()));
            return OK;
        }
    }

    /** One line, and the numbers behind it as JSON. */
    @Command(name = "summary", description = "One line, and the numbers behind it as json.")
    static final class Summary extends BenchCommand {

        @Option(names = "--days", paramLabel = "INTEGER", description = "how far back to look")
        private int days = 7;

        @Override
        int run(Context context) {
            UsageReport report = report(context, days);
            context.say(
                    report.describe(),
                    fields(
                            "start", report.start(),
                            "end", report.end(),
                            "hours", report.hours(),
                            "leases", report.leases(),
                            "waiting", report.waiting(),
                            "busiest", report.busiest(),
                            "utilisation", report.utilisation(),
                            "holder_hours", report.holderHours(),
                            "idle", report.idle()));
            return OK;
        }
    }
}
