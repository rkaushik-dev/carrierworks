package io.github.rkaushikdev.carrierworks.scheduling;

import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Taking a board off somebody who is using it, and the margins the lab settled on after the first
 * month of arguments.
 *
 * <p>This is the part of the scheduler that makes people angry, so the rules are narrow and every
 * refusal says which one applied.
 *
 * <p>A lease can only be preempted if its holder said it could be. A release verification run marks
 * itself unpreemptible and that is the end of the conversation, whatever anybody else's priority
 * is.
 *
 * <p>The claimant has to beat the holder by a margin rather than by a point. Priority five taking a
 * board off priority six all afternoon is not prioritisation, it is churn.
 *
 * <p>A lease that has only just started is left alone, because the cost of preempting is whatever
 * work has been done so far and there is a minimum below which it is not worth anybody's time. A
 * lease that is nearly over is also left alone, for the same reason from the other end: waiting four
 * minutes is cheaper than throwing away twenty.
 *
 * <p>And a board in the middle of being programmed is never taken, at any priority, because
 * interrupting a flash write leaves a device that will not boot.
 *
 * <p>Thermal is the exception to all of it. A board over its critical temperature comes off its
 * holder immediately, because the alternative is damage.
 *
 * @param margin how many priority points the claimant has to beat the holder by, at least one
 * @param minimumHeld how long a lease has to have run before it may be taken
 * @param minimumLeft how long a lease has to have left before it is worth taking
 */
public record PreemptionPolicy(int margin, Duration minimumHeld, Duration minimumLeft) {

    /** The margin unless the lab says otherwise. */
    public static final int DEFAULT_MARGIN = 2;

    /** Both minimums unless the lab says otherwise. */
    public static final Duration DEFAULT_MINIMUM = Duration.ofMinutes(5);

    // Cheapest first: the least important holder, then the shortest running, then the id.
    private static final Comparator<Lease> CHEAPEST = Comparator.comparing(Lease::priority, Comparator.reverseOrder())
            .thenComparing(Lease::takenAt, Comparator.reverseOrder())
            .thenComparing(Lease::id);

    public PreemptionPolicy {
        if (margin < 1) {
            throw new ValidationError("a margin of nothing is not a margin", "margin");
        }
        if (minimumHeld.isNegative() || minimumLeft.isNegative()) {
            throw new ValidationError("the minimums are not negative", "policy");
        }
    }

    /** The lab's settled policy: a margin of two and five minutes at either end. */
    public PreemptionPolicy() {
        this(DEFAULT_MARGIN, DEFAULT_MINIMUM, DEFAULT_MINIMUM);
    }

    public PreemptionPolicy withMargin(int value) {
        return new PreemptionPolicy(value, minimumHeld, minimumLeft);
    }

    public PreemptionPolicy withMinimumHeld(Duration value) {
        return new PreemptionPolicy(margin, value, minimumLeft);
    }

    public PreemptionPolicy withMinimumLeft(Duration value) {
        return new PreemptionPolicy(margin, minimumHeld, value);
    }

    /** {@link #evaluate(Lease, Instant, int, BoardStatus, boolean)} for a board that is simply leased. */
    public PreemptionDecision evaluate(Lease lease, Instant now, int claimantPriority) {
        return evaluate(lease, now, claimantPriority, BoardStatus.LEASED, false);
    }

    /** {@link #evaluate(Lease, Instant, int, BoardStatus, boolean)} outside an emergency. */
    public PreemptionDecision evaluate(Lease lease, Instant now, int claimantPriority, BoardStatus boardState) {
        return evaluate(lease, now, claimantPriority, boardState, false);
    }

    /**
     * Decide whether this lease can be taken for a claimant.
     *
     * <p>An emergency is allowed whatever the rules say. Otherwise a lease that is no longer held
     * stops there with that one reason, and for a held lease every rule it breaks is reported, not
     * just the first.
     *
     * @param claimantPriority the priority of whoever wants the board
     * @param boardState the state the board is in now
     * @param emergency whether the board has to come off its holder regardless
     */
    public PreemptionDecision evaluate(
            Lease lease, Instant now, int claimantPriority, BoardStatus boardState, boolean emergency) {
        if (emergency) {
            return new PreemptionDecision(lease.id(), lease.board(), List.of(), true);
        }
        if (!lease.active(now)) {
            return new PreemptionDecision(lease.id(), lease.board(), List.of("that lease is not held any more"), false);
        }
        List<String> blockers = new ArrayList<>();
        if (boardState == BoardStatus.PROGRAMMING) {
            blockers.add("the board is being programmed");
        }
        if (!lease.preemptible()) {
            blockers.add("the holder marked the lease unpreemptible");
        }
        if (claimantPriority > lease.priority() - margin) {
            blockers.add("priority " + claimantPriority + " does not beat " + lease.priority() + " by " + margin);
        }
        Duration held = Duration.between(lease.takenAt(), now);
        if (held.compareTo(minimumHeld) < 0) {
            blockers.add("the lease is only " + wholeMinutes(held) + " minutes old");
        }
        Duration left = lease.remaining(now);
        if (left.compareTo(minimumLeft) <= 0) {
            blockers.add("it has " + wholeMinutes(left) + " minutes left to run");
        }
        return new PreemptionDecision(lease.id(), lease.board(), blockers, false);
    }

    /** {@link #choose(List, Instant, int, Map)} with every board simply leased. */
    public Optional<Lease> choose(List<Lease> leases, Instant now, int claimantPriority) {
        return choose(leases, now, claimantPriority, Map.of());
    }

    /**
     * The lease to take, out of everything that could be taken.
     *
     * <p>The cheapest one, which means the lowest priority holder and then the one that has been
     * running the shortest time, because that is the least work thrown away.
     *
     * @param boardStates the state of each board by tag; a board missing here is taken to be leased
     */
    public Optional<Lease> choose(
            List<Lease> leases, Instant now, int claimantPriority, Map<String, BoardStatus> boardStates) {
        return leases.stream()
                .filter(lease -> evaluate(
                                lease,
                                now,
                                claimantPriority,
                                boardStates.getOrDefault(lease.board(), BoardStatus.LEASED))
                        .allowed())
                .min(CHEAPEST);
    }

    /** Whole minutes, rounded down, as the refusals print them. */
    private static long wholeMinutes(Duration duration) {
        return Math.floorDiv(duration.getSeconds(), 60);
    }
}
