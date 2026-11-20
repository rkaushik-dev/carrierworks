package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.RequirementSet;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardProfile;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.scheduling.BoardMatcher;
import io.github.rkaushikdev.carrierworks.scheduling.PreemptionPolicy;
import io.github.rkaushikdev.carrierworks.scheduling.Request;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The waiting list, and the pass that hands boards out.
 *
 * <p>A request is somebody asking for a board by what it has to do rather than by name. The queue
 * exists because the honest answer to that is often "not yet": every board that matches is busy,
 * and the person asking would rather wait in a line they can see than poll the bench themselves.
 *
 * <p>Ordering is priority first, then how long the request has waited, then the order they arrived
 * in. Fairness is the quota: one team cannot hold more than its share while another team's request
 * sits behind it, and a request from a team at its quota is skipped rather than refused, because the
 * quota will free up on its own.
 *
 * <p>Dispatch takes the unit of work with it. A request that gets a board and a lease that gets
 * written are the same commit, so a crash between them is not a state anybody has to clean up.
 *
 * <p>Preemption is off unless a caller asks for it, and even then it is the last thing tried: every
 * free board is offered first, and a lease is only taken when nothing free will do. What may be
 * taken is not decided here. That is the {@link PreemptionPolicy}, which knows about margins,
 * minimum hold times and the board being programmed right now.
 */
public final class Scheduling {

    private Scheduling() {}

    /** Put a request on the waiting list on the standard terms. */
    public static Request submit(UnitOfWork unit, String holder, String purpose, String requirements, Instant now) {
        return submit(unit, holder, purpose, requirements, now, Request.Terms.standard());
    }

    /**
     * Put a request on the waiting list.
     *
     * @param requirements what the board has to do, separated by commas or newlines
     * @throws ValidationError when a requirement does not read, or the terms do not hold up
     */
    public static Request submit(
            UnitOfWork unit, String holder, String purpose, String requirements, Instant now, Request.Terms terms) {
        return add(unit, holder, purpose, RequirementSet.parse(requirements), now, terms);
    }

    /**
     * Put a request on the waiting list, its requirements written one to an entry.
     *
     * @throws ValidationError when a requirement does not read, or the terms do not hold up
     */
    public static Request submit(
            UnitOfWork unit,
            String holder,
            String purpose,
            Collection<String> requirements,
            Instant now,
            Request.Terms terms) {
        return add(unit, holder, purpose, RequirementSet.parse(requirements), now, terms);
    }

    private static Request add(
            UnitOfWork unit, String holder, String purpose, RequirementSet wanted, Instant now, Request.Terms terms) {
        return unit.requests().add(new Request(unit.requests().nextId(), holder, purpose, wanted, now, terms));
    }

    /**
     * Take a request off the list.
     *
     * @throws ValidationError when it is already cancelled or satisfied
     */
    public static Request cancel(UnitOfWork unit, String requestId, Instant now) {
        return unit.requests().save(unit.requests().get(requestId).cancel(now));
    }

    /** Everything still waiting, in the order it will be served. */
    public static List<Request> waiting(UnitOfWork unit, Instant now) {
        return unit.requests().ordered(now);
    }

    /** A standard pass: no quotas, no limit, no preemption, on the default settings. */
    public static DispatchOutcome dispatch(UnitOfWork unit, Instant now) {
        return dispatch(unit, now, DispatchOptions.standard());
    }

    /**
     * One pass over the waiting list, taking every board it can.
     *
     * <p>Expired leases are swept first, so a board a dead job was holding is in the pool. Boards are
     * taken out of the pool as they are handed out, so two requests in one pass never both get the
     * same board. A preempted board was never in the pool to begin with.
     *
     * @throws ValidationError when the limit is negative
     */
    public static DispatchOutcome dispatch(UnitOfWork unit, Instant now, DispatchOptions options) {
        if (options.limit() < 0) {
            throw new ValidationError(
                    "a dispatch limit is not negative", "limit", Details.of("given", options.limit()));
        }
        Leasing.sweepExpired(unit, now);
        Map<String, Board> free = new LinkedHashMap<>();
        unit.boards().available().forEach(board -> free.put(board.tag(), board));
        List<DispatchOutcome.Assigned> assigned = new ArrayList<>();
        Map<String, String> skipped = new LinkedHashMap<>();
        List<DispatchOutcome.Preempted> taken = new ArrayList<>();
        for (Request request : unit.requests().ordered(now)) {
            if (options.limit() > 0 && assigned.size() >= options.limit()) {
                skipped.put(request.id(), "not reached on this pass");
                continue;
            }
            Optional<String> refusal = overQuota(
                    unit, request, now, Optional.ofNullable(options.quotas().get(request.holder())));
            if (refusal.isPresent()) {
                skipped.put(request.id(), refusal.get());
                continue;
            }
            Optional<Board> board = BoardMatcher.choose(List.copyOf(free.values()), request.requirements(), now);
            if (board.isEmpty() && options.preempt()) {
                Optional<Lease> bumped = preemptFor(unit, request, now, options.policy());
                if (bumped.isPresent()) {
                    taken.add(new DispatchOutcome.Preempted(
                            bumped.get().id(), bumped.get().board()));
                    board = Optional.of(unit.boards().get(bumped.get().board()));
                }
            }
            if (board.isEmpty()) {
                skipped.put(request.id(), "nothing free matches what it asked for");
                continue;
            }
            String tag = board.get().tag();
            Lease lease = Leasing.acquire(
                    unit,
                    tag,
                    request.holder(),
                    request.purpose(),
                    now,
                    new LeaseTerms(request.minutes(), request.priority(), request.preemptible()),
                    options.settings());
            unit.requests().save(request.satisfy(now), Optional.of(tag));
            free.remove(tag);
            assigned.add(new DispatchOutcome.Assigned(request.id(), tag, lease.id()));
        }
        return new DispatchOutcome(assigned, skipped, taken);
    }

    /** Why a request has to wait for its holder's quota, or nothing when it does not. */
    private static Optional<String> overQuota(UnitOfWork unit, Request request, Instant now, Optional<Integer> quota) {
        if (quota.isEmpty()) {
            return Optional.empty();
        }
        int held = unit.leases().heldBy(request.holder(), now).size();
        if (held < quota.get()) {
            return Optional.empty();
        }
        return Optional.of(request.holder() + " already holds " + held + " boards, quota is " + quota.get());
    }

    /**
     * Take a board off somebody, if the policy and the requirements allow it.
     *
     * <p>Only leases on boards that would actually satisfy the request are offered to the policy.
     * Bumping somebody off a board that then turns out not to have the right converter fitted is the
     * worst outcome available here.
     */
    private static Optional<Lease> preemptFor(UnitOfWork unit, Request request, Instant now, PreemptionPolicy policy) {
        List<Lease> wanted = unit.leases().active(now).stream()
                .filter(lease -> BoardProfile.matches(unit.boards().get(lease.board()), request.requirements()))
                .toList();
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        Map<String, BoardStatus> states = new LinkedHashMap<>();
        wanted.forEach(lease ->
                states.put(lease.board(), unit.boards().state(lease.board()).state()));
        Optional<Lease> chosen = policy.choose(wanted, now, request.priority(), states);
        chosen.ifPresent(lease -> Leasing.release(unit, lease.id(), now, "preempted by " + request.id()));
        return chosen;
    }
}
