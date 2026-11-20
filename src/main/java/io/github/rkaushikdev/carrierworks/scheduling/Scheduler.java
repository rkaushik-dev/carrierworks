package io.github.rkaushikdev.carrierworks.scheduling;

import io.github.rkaushikdev.carrierworks.BenchCalendar;
import io.github.rkaushikdev.carrierworks.Conflict;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardBook;
import io.github.rkaushikdev.carrierworks.inventory.BoardProfile;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.leases.LeaseStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The scheduler: the queue, the inventory and the leases in one place.
 *
 * <p>Everything it does happens on a tick. Somebody calls {@link #dispatch(Instant)}, it walks the
 * queue in order, and for each request it tries to find a board. Nothing runs in the background,
 * because a bench that schedules while nobody is looking is a bench that cannot be reasoned about
 * from its logs.
 *
 * <p>Order matters and it is not first come first served. The queue decides who is looked at
 * first; this decides whether they can be served, and a request that cannot be is left where it is
 * rather than being dropped or pushed back. Three things stop a request being served: the bench is
 * shut and the request needs hands, the holder is already at their quota, or nothing free matches.
 * Only the last one is worth trying preemption for.
 *
 * <p>A refusal is recorded with its reason for every request that is not served, so that "why is
 * my job still queued" has an answer in the same tick that made the decision.
 */
public final class Scheduler {

    // Who the scheduler says moved a board when it moves one on its own account.
    private static final String ACTOR = "scheduler";
    private static final String PREEMPTED = "preempted";
    private static final String FAULTED = "board faulted";
    private static final String NOTHING_MATCHES = "nothing free matches what it asked for";

    private final BoardBook book;
    private final LeaseStore leases;
    private final RequestQueue queue;
    private final PreemptionPolicy policy;
    private final Optional<BenchCalendar> calendar;
    private final Map<String, Integer> quotas;

    /** A scheduler under the lab's usual preemption policy, with no calendar and no quotas. */
    public Scheduler(BoardBook book, LeaseStore leases, RequestQueue queue) {
        this(builder(book, leases, queue));
    }

    private Scheduler(Builder builder) {
        this.book = builder.book;
        this.leases = builder.leases;
        this.queue = builder.queue;
        this.policy = builder.policy;
        this.calendar = builder.calendar;
        this.quotas = Collections.unmodifiableMap(new LinkedHashMap<>(builder.quotas));
    }

    /** A scheduler over these three, with its policy, calendar and quotas still to be set. */
    public static Builder builder(BoardBook book, LeaseStore leases, RequestQueue queue) {
        return new Builder(book, leases, queue);
    }

    /** The boards it hands out. */
    public BoardBook book() {
        return book;
    }

    /** The leases it hands them out on. */
    public LeaseStore leases() {
        return leases;
    }

    /** The requests waiting for a board. */
    public RequestQueue queue() {
        return queue;
    }

    /** The rules for taking a board off somebody. */
    public PreemptionPolicy policy() {
        return policy;
    }

    /** The staffed hours a request that needs hands waits for, if the bench keeps any. */
    public Optional<BenchCalendar> calendar() {
        return calendar;
    }

    /** The most boards each holder may have at once, by holder; a holder missing here has no limit. */
    public Map<String, Integer> quotas() {
        return quotas;
    }

    /** {@link #dispatch(Instant, Map)} with no board counted as faulting. */
    public DispatchResult dispatch(Instant now) {
        return dispatch(now, Map.of());
    }

    /**
     * One pass over the queue.
     *
     * <p>Expired leases are swept first, then the queue is walked in order, fair share counted from
     * the leases held now. Each request is served from the free boards, or by preemption when none
     * of them match, or refused with its reason.
     *
     * @param faults how many recent faults each board has had, by tag, which pushes it down the
     *     ranking
     */
    public DispatchResult dispatch(Instant now, Map<String, Integer> faults) {
        leases.sweep(now);
        List<Assignment> assignments = new ArrayList<>();
        Map<String, String> refusals = new LinkedHashMap<>();
        for (Request request : queue.ordered(leases.countsByHolder(now))) {
            Optional<String> refusal = blocked(request, now);
            if (refusal.isPresent()) {
                refusals.put(request.id(), refusal.get());
                continue;
            }
            switch (serve(request, now, faults)) {
                case Served served -> assignments.add(served.assignment());
                case Refused refused -> refusals.put(request.id(), refused.reason());
            }
        }
        return new DispatchResult(assignments, refusals);
    }

    /** {@link #release(String, Instant, String)} giving the plain reason. */
    public Lease release(String leaseId, Instant now) {
        return release(leaseId, now, Lease.RELEASED);
    }

    /**
     * Give a board back and put it into service again.
     *
     * <p>A board that is leased or being programmed goes back to available; one in any other state,
     * or no longer in the book, is left where it is.
     */
    public Lease release(String leaseId, Instant now, String reason) {
        Lease lease = leases.release(leaseId, now, reason);
        if (book.has(lease.board())) {
            BoardStatus state = book.state(lease.board()).state();
            if (state == BoardStatus.PROGRAMMING) {
                book.moveTo(lease.board(), BoardStatus.LEASED, now, "", lease.holder());
            }
            if (state == BoardStatus.LEASED || state == BoardStatus.PROGRAMMING) {
                book.moveTo(lease.board(), BoardStatus.AVAILABLE, now, "", lease.holder());
            }
        }
        return lease;
    }

    /**
     * Take a board out of service, releasing whatever holds it.
     *
     * <p>A board already faulted is not moved again.
     *
     * @throws ValidationError when the reason is blank
     */
    public void fault(String board, Instant now, String reason) {
        if (reason.isBlank()) {
            throw new ValidationError("a fault needs a reason", "reason");
        }
        leases.forBoard(board, now).ifPresent(held -> leases.release(held.id(), now, FAULTED));
        if (book.state(board).state() != BoardStatus.FAULTED) {
            book.moveTo(board, BoardStatus.FAULTED, now, reason, ACTOR);
        }
    }

    private Optional<String> blocked(Request request, Instant now) {
        if (request.needsHands() && calendar.isPresent()) {
            Optional<String> shut = calendar.get().blocked(now);
            if (shut.isPresent()) {
                return Optional.of("needs somebody in the lab and " + shut.get());
            }
        }
        Integer quota = quotas.get(request.holder());
        if (quota != null) {
            int held = leases.heldBy(request.holder(), now).size();
            if (held >= quota) {
                return Optional.of(request.holder() + " already holds " + held + " boards, quota is " + quota);
            }
        }
        return Optional.empty();
    }

    private Outcome serve(Request request, Instant now, Map<String, Integer> faults) {
        Optional<Board> free = BoardMatcher.choose(book.available(), request.requirements(), now, lastUsed(), faults);
        Optional<Lease> preempted = Optional.empty();
        Board board;
        if (free.isPresent()) {
            board = free.get();
        } else {
            preempted = preemptFor(request, now);
            if (preempted.isEmpty()) {
                return new Refused(NOTHING_MATCHES);
            }
            board = book.board(preempted.get().board());
        }
        Lease lease;
        try {
            lease = leases.acquire(
                    board.tag(),
                    request.holder(),
                    request.purpose(),
                    now,
                    request.minutes(),
                    request.priority(),
                    request.preemptible());
        } catch (Conflict clash) {
            // The sweep should prevent this, but a board marked available by hand can still be held.
            return new Refused(clash.message());
        }
        book.moveTo(board.tag(), BoardStatus.LEASED, now, "", request.holder());
        queue.satisfy(request.id(), now);
        return new Served(new Assignment(request.id(), board.tag(), lease, preempted));
    }

    /** Take a board off somebody, if the rules allow it and one matches. */
    private Optional<Lease> preemptFor(Request request, Instant now) {
        List<Lease> candidates = leases.active(now).stream()
                .filter(lease -> book.has(lease.board())
                        && BoardProfile.matches(book.board(lease.board()), request.requirements()))
                .toList();
        Map<String, BoardStatus> states = new HashMap<>();
        book.all()
                .forEach(
                        board -> states.put(board.tag(), book.state(board.tag()).state()));
        Optional<Lease> taken = policy.choose(candidates, now, request.priority(), states);
        taken.ifPresent(lease -> {
            leases.release(lease.id(), now, PREEMPTED);
            book.moveTo(lease.board(), BoardStatus.AVAILABLE, now, "", ACTOR);
        });
        return taken;
    }

    /** When each board was last held until, by tag, across every lease ever issued. */
    private Map<String, Instant> lastUsed() {
        Map<String, Instant> used = new HashMap<>();
        for (Lease lease : leases.all()) {
            Instant end = lease.releasedAt().orElse(lease.expiresAt());
            used.merge(lease.board(), end, (earlier, later) -> later.isAfter(earlier) ? later : earlier);
        }
        return used;
    }

    /** What serving one request came to. */
    private sealed interface Outcome permits Served, Refused {}

    private record Served(Assignment assignment) implements Outcome {}

    private record Refused(String reason) implements Outcome {}

    /** A scheduler with its optional parts set one at a time. */
    public static final class Builder {

        private final BoardBook book;
        private final LeaseStore leases;
        private final RequestQueue queue;
        private PreemptionPolicy policy = new PreemptionPolicy();
        private Optional<BenchCalendar> calendar = Optional.empty();
        private Map<String, Integer> quotas = Map.of();

        private Builder(BoardBook book, LeaseStore leases, RequestQueue queue) {
            this.book = book;
            this.leases = leases;
            this.queue = queue;
        }

        /** The rules for taking a board off somebody, instead of the lab's usual ones. */
        public Builder policy(PreemptionPolicy value) {
            policy = value;
            return this;
        }

        /** The staffed hours a request that needs hands has to wait for. */
        public Builder calendar(BenchCalendar value) {
            calendar = Optional.of(value);
            return this;
        }

        /** The most boards each holder may have at once, keyed by holder as stored. */
        public Builder quotas(Map<String, Integer> value) {
            quotas = value;
            return this;
        }

        /** The scheduler. */
        public Scheduler build() {
            return new Scheduler(this);
        }
    }
}
