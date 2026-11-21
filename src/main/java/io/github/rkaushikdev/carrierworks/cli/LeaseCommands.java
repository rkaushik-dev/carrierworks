package io.github.rkaushikdev.carrierworks.cli;

import io.github.rkaushikdev.carrierworks.Conflict;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.RequirementSet;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardProfile;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.scheduling.BoardMatcher;
import io.github.rkaushikdev.carrierworks.services.LeaseTerms;
import io.github.rkaushikdev.carrierworks.services.Leasing;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code fpga lease}: taking a board and giving it back, from a terminal beside the rack.
 *
 * <p>The one command worth explaining is {@code take}. It can be handed a board, or it can be handed
 * what the job needs and pick one, and the second form is what people actually use: nobody wants
 * bx-14, they want something with a converter on it that nobody else is using.
 */
@Command(
        name = "lease",
        description = "Who has which board.",
        subcommands = {
            LeaseCommands.Expiring.class,
            LeaseCommands.ListLeases.class,
            LeaseCommands.Release.class,
            LeaseCommands.Renew.class,
            LeaseCommands.Sweep.class,
            LeaseCommands.Take.class
        })
final class LeaseCommands {

    private LeaseCommands() {}

    /** {@code 2026-02-13T10:00+00:00}: when a lease runs out, to the minute. */
    private static String until(Lease lease) {
        return Times.iso(lease.expiresAt(), Times.Precision.MINUTES);
    }

    /** Every board being held right now. */
    @Command(name = "list", description = "Every board being held right now.")
    static final class ListLeases extends BenchCommand {

        @Option(names = "--holder", paramLabel = "TEXT", description = "one team rather than all of them")
        private Optional<String> holder = Optional.empty();

        @Override
        int run(Context context) {
            Instant now = context.now();
            List<Map<String, Object>> rows = context.apply(unit -> {
                Optional<String> one = holder.filter(name -> !name.isEmpty());
                List<Lease> held = one.isPresent()
                        ? unit.leases().heldBy(one.get(), now)
                        : unit.leases().active(now);
                return held.stream()
                        .map(lease -> fields(
                                "lease", lease.id(),
                                "board", lease.board(),
                                "holder", lease.holder(),
                                "purpose", lease.purpose(),
                                "until", until(lease),
                                "priority", lease.priority()))
                        .toList();
            });
            context.emit(rows, "lease", "board", "holder", "purpose", "until", "priority");
            return OK;
        }
    }

    /** Take a board, either by name or by what it has to be able to do. */
    @Command(name = "take", description = "Take a board, either by name or by what it has to be able to do.")
    static final class Take extends BenchCommand {

        @Option(names = "--board", paramLabel = "TEXT", description = "a particular board")
        private Optional<String> board = Optional.empty();

        @Option(names = "--needs", paramLabel = "TEXT", description = "what the job needs, comma separated")
        private Optional<String> wanted = Optional.empty();

        @Option(names = "--holder", paramLabel = "TEXT", required = true, description = "who is taking it")
        private String holder;

        @Option(names = "--purpose", paramLabel = "TEXT", required = true, description = "what it is for")
        private String purpose;

        @Option(names = "--minutes", paramLabel = "INTEGER", description = "how long, up to the ceiling")
        private Optional<Integer> minutes = Optional.empty();

        @Option(names = "--priority", paramLabel = "INTEGER", description = "one is most urgent")
        private int priority = Lease.DEFAULT_PRIORITY;

        @Option(names = "--keep", description = "refuse to be preempted")
        private boolean keep;

        @Override
        int run(Context context) {
            Optional<String> tag = board.filter(name -> !name.isEmpty());
            String needs = wanted.orElse("");
            if (tag.isEmpty() && needs.isEmpty()) {
                throw new UsageError("say which board, or what it needs");
            }
            Instant now = context.now();
            LeaseTerms terms = LeaseTerms.standard()
                    .withMinutes(minutes.orElse(0))
                    .withPriority(priority)
                    .withPreemptible(!keep);
            Lease lease = context.apply(unit -> Leasing.acquire(
                    unit,
                    tag.orElseGet(() -> pick(unit, needs, now)),
                    holder,
                    purpose,
                    now,
                    terms,
                    context.settings()));
            context.say(
                    lease.board() + " is yours until " + until(lease) + " (" + lease.id() + ")",
                    fields(
                            "lease", lease.id(),
                            "board", lease.board(),
                            "holder", lease.holder(),
                            "expires_at", lease.expiresAt()));
            return OK;
        }

        /**
         * The free board that best fits what was asked for.
         *
         * @throws Conflict when none of them will do, saying why each one would not
         */
        private static String pick(UnitOfWork unit, String needs, Instant now) {
            RequirementSet requirements = RequirementSet.parse(needs);
            List<Board> free = unit.boards().available();
            return BoardMatcher.choose(free, requirements, now)
                    .map(Board::tag)
                    .orElseThrow(() -> new Conflict(
                            "nothing free matches what you asked for",
                            Details.of(
                                    "wanted",
                                    needs,
                                    "considered",
                                    free.stream()
                                            .map(board -> board.tag() + ": " + BoardProfile.whyNot(board, requirements))
                                            .collect(Collectors.joining("; ")))));
        }
    }

    /** Give a board back. */
    @Command(name = "release", description = "Give a board back.")
    static final class Release extends BenchCommand {

        @Parameters(paramLabel = "LEASE_ID")
        private String leaseId;

        @Option(names = "--reason", paramLabel = "TEXT", description = "why it is being given back")
        private String reason = Lease.RELEASED;

        @Override
        int run(Context context) {
            Lease closed = context.apply(unit -> Leasing.release(unit, leaseId, context.now(), reason));
            context.say(
                    closed.board() + " is free again",
                    fields("lease", closed.id(), "board", closed.board(), "reason", reason));
            return OK;
        }
    }

    /** Keep a board a while longer, up to the ceiling. */
    @Command(name = "renew", description = "Keep a board a while longer, up to the ceiling.")
    static final class Renew extends BenchCommand {

        @Parameters(paramLabel = "LEASE_ID")
        private String leaseId;

        @Option(names = "--minutes", paramLabel = "INTEGER", description = "how much longer")
        private int minutes = 60;

        @Override
        int run(Context context) {
            Lease renewed =
                    context.apply(unit -> Leasing.renew(unit, leaseId, context.now(), minutes, context.settings()));
            context.say(
                    renewed.board() + " is yours until " + until(renewed),
                    fields("lease", renewed.id(), "board", renewed.board(), "expires_at", renewed.expiresAt()));
            return OK;
        }
    }

    /** Close the leases that have run out and free their boards. */
    @Command(name = "sweep", description = "Close the leases that have run out and free their boards.")
    static final class Sweep extends BenchCommand {

        @Override
        int run(Context context) {
            List<Lease> closed = context.apply(unit -> Leasing.sweepExpired(unit, context.now()));
            if (closed.isEmpty()) {
                context.say("nothing had run out", fields("closed", List.of()));
                return OK;
            }
            List<Map<String, Object>> rows = closed.stream()
                    .map(lease -> fields("lease", lease.id(), "board", lease.board(), "holder", lease.holder()))
                    .toList();
            context.emit(rows, "lease", "board", "holder");
            return OK;
        }
    }

    /** Leases about to run out, soonest first. */
    @Command(name = "expiring", description = "Leases about to run out, soonest first.")
    static final class Expiring extends BenchCommand {

        @Option(names = "--minutes", paramLabel = "INTEGER", description = "how far ahead to look")
        private int minutes = 15;

        @Option(names = "--remind", description = "write a line into the trail")
        private boolean remind;

        @Override
        int run(Context context) {
            Duration window = Duration.ofMinutes(minutes);
            Instant now = context.now();
            List<Map<String, Object>> rows = context.apply(unit -> {
                List<Lease> found = remind ? Leasing.remind(unit, now, window) : Leasing.expiring(unit, now, window);
                return found.stream()
                        .map(lease -> fields(
                                "lease", lease.id(),
                                "asset", lease.board(),
                                "holder", lease.holder(),
                                "left", Math.floorDiv(lease.remaining(now).getSeconds(), 60) + "m",
                                "renewals", lease.renewals()))
                        .toList();
            });
            context.emit(rows, "lease", "asset", "holder", "left", "renewals");
            return OK;
        }
    }
}
