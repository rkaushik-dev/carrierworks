package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.Requirement;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.scheduling.Request;
import io.github.rkaushikdev.carrierworks.services.DispatchOptions;
import io.github.rkaushikdev.carrierworks.services.DispatchOutcome;
import io.github.rkaushikdev.carrierworks.services.Scheduling;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code fpga queue}: the waiting list from the command line.
 *
 * <p>Somebody asks for a board by what it has to do, and either gets one now or gets a place in the
 * line. The two commands people actually use are submit and list: submit because it is the polite
 * alternative to taking a board somebody else is about to need, and list because the first question
 * after waiting ten minutes is where you are in the queue.
 *
 * <p>Dispatch is the pass that hands boards out, and it is not on a timer here. A site runs it from
 * cron, or after every release, or by hand when somebody gives a board back early.
 */
@Command(
        name = "queue",
        description = "Requests waiting for a board.",
        subcommands = {
            QueueCommands.Cancel.class,
            QueueCommands.Dispatch.class,
            QueueCommands.ListRequests.class,
            QueueCommands.Submit.class
        })
final class QueueCommands {

    private QueueCommands() {}

    private static String stateOf(Request request) {
        if (request.satisfiedAt().isPresent()) {
            return "served";
        }
        if (request.cancelledAt().isPresent()) {
            return "cancelled";
        }
        return "waiting";
    }

    /** Ask for a board and take a place in the line. */
    @Command(name = "submit", description = "Ask for a board and take a place in the line.")
    static final class Submit extends BenchCommand {

        @Option(names = "--holder", paramLabel = "TEXT", required = true, description = "who is asking")
        private String holder;

        @Option(names = "--purpose", paramLabel = "TEXT", required = true, description = "what it is for")
        private String purpose;

        @Option(names = "--needs", paramLabel = "TEXT", description = "requirements, comma separated")
        private String wants = "";

        @Option(names = "--priority", paramLabel = "INTEGER", description = "one is urgent, nine is whenever")
        private int priority = Lease.DEFAULT_PRIORITY;

        @Option(names = "--minutes", paramLabel = "INTEGER", description = "how long the lease should run for")
        private int minutes = Request.DEFAULT_MINUTES;

        @Option(names = "--no-preempt", description = "refuse to be bumped by anything")
        private boolean noPreempt;

        @Option(names = "--needs-hands", description = "somebody has to be in the lab for this")
        private boolean needsHands;

        private record Queued(Request request, int ahead) {}

        @Override
        int run(Context context) {
            Instant now = context.now();
            Request.Terms terms = Request.Terms.standard()
                    .withPriority(priority)
                    .withMinutes(minutes)
                    .withPreemptible(!noPreempt)
                    .withNeedsHands(needsHands);
            Queued queued = context.apply(unit -> {
                Request request = Scheduling.submit(unit, holder, purpose, wants, now, terms);
                int ahead = (int) unit.requests().ordered(now).stream()
                        .filter(entry -> !entry.id().equals(request.id()))
                        .count();
                return new Queued(request, ahead);
            });
            Request request = queued.request();
            context.say(
                    request.id() + " queued, " + queued.ahead() + " ahead of it",
                    fields("request", request.id(), "holder", request.holder(), "ahead", queued.ahead()));
            return OK;
        }
    }

    /** What is waiting, in the order it will be served. */
    @Command(name = "list", description = "What is waiting, in the order it will be served.")
    static final class ListRequests extends BenchCommand {

        @Option(names = "--holder", paramLabel = "TEXT", description = "one team rather than everybody")
        private Optional<String> holder = Optional.empty();

        @Option(names = "--all", description = "closed requests as well")
        private boolean everything;

        @Override
        int run(Context context) {
            Instant now = context.now();
            List<Map<String, Object>> rows = context.apply(unit -> {
                List<Request> found;
                if (holder.isPresent()) {
                    found = unit.requests().forHolder(holder.get());
                } else if (everything) {
                    found = unit.requests().all();
                } else {
                    found = unit.requests().ordered(now);
                }
                return found.stream()
                        .map(entry -> {
                            String needs = entry.requirements().requirements().stream()
                                    .map(Requirement::text)
                                    .collect(Collectors.joining(","));
                            String board = unit.requests().boardFor(entry.id());
                            return fields(
                                    "request", entry.id(),
                                    "holder", entry.holder(),
                                    "priority", entry.priority(),
                                    "needs", needs.isEmpty() ? "anything" : needs,
                                    "waited", Durations.clock(entry.waited(now)),
                                    "state", stateOf(entry),
                                    "board", board.isEmpty() ? "-" : board);
                        })
                        .toList();
            });
            context.emit(rows, "request", "holder", "priority", "needs", "waited", "state", "board");
            return OK;
        }
    }

    /** Give up a place in the line. */
    @Command(name = "cancel", description = "Give up a place in the line.")
    static final class Cancel extends BenchCommand {

        @Parameters(paramLabel = "REQUEST_ID")
        private String requestId;

        @Override
        int run(Context context) {
            Request dropped = context.apply(unit -> Scheduling.cancel(unit, requestId, context.now()));
            context.say(dropped.id() + " cancelled", fields("request", dropped.id(), "state", "cancelled"));
            return OK;
        }
    }

    /** One pass over the list, taking every board it can. */
    @Command(name = "dispatch", description = "One pass over the list, taking every board it can.")
    static final class Dispatch extends BenchCommand {

        @Option(names = "--limit", paramLabel = "INTEGER", description = "hand out at most this many boards")
        private int limit;

        @Option(names = "--quota", paramLabel = "TEXT", description = "team=count, repeatable")
        private List<String> quotas = new ArrayList<>();

        @Option(names = "--preempt", description = "take a board off a lower priority holder")
        private boolean preempt;

        @Override
        int run(Context context) {
            DispatchOptions options = DispatchOptions.standard()
                    .withSettings(context.settings())
                    .withQuotas(quotas(quotas))
                    .withLimit(limit)
                    .withPreempt(preempt);
            DispatchOutcome outcome = context.apply(unit -> Scheduling.dispatch(unit, context.now(), options));
            context.say(
                    outcome.describe(),
                    fields(
                            "assigned",
                                    outcome.assigned().stream()
                                            .map(served -> fields(
                                                    "request", served.request(),
                                                    "board", served.board(),
                                                    "lease", served.lease()))
                                            .toList(),
                            "waiting", outcome.refusals(),
                            "preempted",
                                    outcome.preempted().stream()
                                            .map(taken -> fields("lease", taken.lease(), "board", taken.board()))
                                            .toList()));
            return OK;
        }

        /**
         * The repeatable {@code team=count} option, the team lower cased.
         *
         * @throws UsageError when one is not a team, an equals sign and a count
         */
        private static Map<String, Integer> quotas(List<String> given) {
            Map<String, Integer> parsed = new LinkedHashMap<>();
            for (String item : given) {
                int split = item.indexOf('=');
                String team = split < 0 ? item : item.substring(0, split);
                String count = split < 0 ? "" : item.substring(split + 1);
                if (team.isEmpty() || count.isEmpty() || !count.chars().allMatch(Character::isDigit)) {
                    throw new UsageError("a quota looks like team=count, not " + item);
                }
                try {
                    parsed.put(team.strip().toLowerCase(Locale.ROOT), Integer.parseInt(count));
                } catch (NumberFormatException tooLarge) {
                    throw new UsageError("a quota looks like team=count, not " + item);
                }
            }
            return parsed;
        }
    }
}
