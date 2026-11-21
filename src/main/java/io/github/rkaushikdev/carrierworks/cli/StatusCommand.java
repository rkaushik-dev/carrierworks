package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.services.BenchOverview;
import io.github.rkaushikdev.carrierworks.services.Overview;
import java.util.Map;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code fpga status}: the one command somebody runs before they ask anybody anything.
 *
 * <p>It prints the roll up in a shape that fits on a terminal that is already full of something
 * else, and the same numbers as JSON for whatever is drawing the wall display in the lab.
 *
 * <p>With {@code --fail-on-trouble} the exit status carries the health, so this is also what a check
 * script calls: zero when nothing needs a person, and {@value BenchCommand#NEEDS_ATTENTION} when
 * something does.
 */
@Command(name = "status", description = "What the whole bench is doing right now.")
final class StatusCommand extends BenchCommand {

    @Option(names = "--fail-on-trouble", description = "exit non zero when something needs a person")
    private boolean failOnTrouble;

    @Override
    int run(Context context) {
        BenchOverview state = context.apply(unit -> Overview.overview(unit, context.now(), context.settings()));
        if (context.asJson()) {
            context.say(
                    state.describe(),
                    fields(
                            "at", state.at(),
                            "site", state.site(),
                            "boards", state.boards(),
                            "available", state.available(),
                            "leases_held", state.leasesHeld(),
                            "leases_expiring", state.leasesExpiring(),
                            "queue_waiting", state.queueWaiting(),
                            "queue_longest_wait_seconds", state.queueLongestWaitSeconds(),
                            "jobs_queued", state.jobsQueued(),
                            "jobs_running", state.jobsRunning(),
                            "thermal", state.thermal(),
                            "calibration_overdue", state.calibrationOverdue(),
                            "healthy", state.healthy()));
        } else {
            context.echo(state.describe());
            context.echo("boards   " + or(counts(state.boards()), "none"));
            context.echo("leases   " + state.leasesHeld() + " held, " + state.leasesExpiring() + " expiring soon");
            context.echo("queue    " + state.queueWaiting() + " waiting");
            context.echo("jobs     " + state.jobsQueued() + " queued, " + state.jobsRunning() + " running");
            context.echo("thermal  " + or(counts(state.thermal()), "no readings"));
            if (!state.calibrationOverdue().isEmpty()) {
                context.echo("overdue  " + String.join(", ", state.calibrationOverdue()));
            }
        }
        return failOnTrouble && !state.healthy() ? NEEDS_ATTENTION : OK;
    }

    /** {@code available 2, leased 1}. */
    private static String counts(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private static String or(String text, String otherwise) {
        return text.isEmpty() ? otherwise : text;
    }
}
