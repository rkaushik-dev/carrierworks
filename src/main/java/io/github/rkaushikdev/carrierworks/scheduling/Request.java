package io.github.rkaushikdev.carrierworks.scheduling;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.RequirementSet;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Somebody asking for a board they have not got yet.
 *
 * <p>A request is open until it is cancelled or satisfied. It is immutable: cancelling and
 * satisfying one hand back a new request.
 *
 * @param id the request id, as a slug, such as {@code req-00001}
 * @param holder who is asking, as a slug
 * @param purpose what the board is wanted for, never blank
 * @param requirements what the board has to be able to do
 * @param submittedAt when the request went on the queue
 * @param priority {@value Lease#MIN_PRIORITY} is the most urgent, {@value Lease#MAX_PRIORITY} the least
 * @param minutes how long the board is wanted for, always some
 * @param preemptible whether the lease it gets may be taken away by something more urgent
 * @param needsHands whether the job needs somebody in the lab, to reseat a cable or press a button
 * @param cancelledAt when the request was cancelled, if it was
 * @param satisfiedAt when the request got its board, if it has
 */
public record Request(
        String id,
        String holder,
        String purpose,
        RequirementSet requirements,
        Instant submittedAt,
        int priority,
        int minutes,
        boolean preemptible,
        boolean needsHands,
        Optional<Instant> cancelledAt,
        Optional<Instant> satisfiedAt) {

    /** How long a request wants its board unless it says otherwise. */
    public static final int DEFAULT_MINUTES = 60;

    public Request {
        id = Ids.slug(id);
        holder = Ids.slug(holder);
        if (purpose.isBlank()) {
            throw new ValidationError("a request says what it is for", "purpose");
        }
        if (priority < Lease.MIN_PRIORITY || priority > Lease.MAX_PRIORITY) {
            throw new ValidationError("priority is one to nine", "priority");
        }
        if (minutes <= 0) {
            throw new ValidationError("a request asks for some minutes", "minutes");
        }
    }

    /** An open request on the standard terms. */
    public Request(String id, String holder, String purpose, RequirementSet requirements, Instant submittedAt) {
        this(id, holder, purpose, requirements, submittedAt, Terms.standard());
    }

    /** An open request on these terms. */
    public Request(
            String id, String holder, String purpose, RequirementSet requirements, Instant submittedAt, Terms terms) {
        this(
                id,
                holder,
                purpose,
                requirements,
                submittedAt,
                terms.priority(),
                terms.minutes(),
                terms.preemptible(),
                terms.needsHands(),
                Optional.empty(),
                Optional.empty());
    }

    /** Whether the request is still waiting, which means neither cancelled nor satisfied. */
    public boolean isOpen() {
        return cancelledAt.isEmpty() && satisfiedAt.isEmpty();
    }

    /**
     * How long the request has waited: up to when it was satisfied or cancelled, otherwise up to
     * now, and never less than nothing.
     */
    public Duration waited(Instant now) {
        Instant end = satisfiedAt.or(() -> cancelledAt).orElse(now);
        Duration waited = Duration.between(submittedAt, end);
        return waited.isNegative() ? Duration.ZERO : waited;
    }

    /**
     * The request withdrawn at {@code now}.
     *
     * @throws ValidationError when it is already cancelled or satisfied
     */
    public Request cancel(Instant now) {
        checkOpen();
        return closed(Optional.of(now), satisfiedAt);
    }

    /**
     * The request marked as having got its board at {@code now}.
     *
     * @throws ValidationError when it is already cancelled or satisfied
     */
    public Request satisfy(Instant now) {
        checkOpen();
        return closed(cancelledAt, Optional.of(now));
    }

    /** {@code req-00001 team-a priority 3 for capture run}. */
    public String describe() {
        return id + " " + holder + " priority " + priority + " for " + purpose;
    }

    private void checkOpen() {
        if (!isOpen()) {
            throw new ValidationError("that request is already closed", "request", Details.of("id", id));
        }
    }

    private Request closed(Optional<Instant> cancelled, Optional<Instant> satisfied) {
        return new Request(
                id,
                holder,
                purpose,
                requirements,
                submittedAt,
                priority,
                minutes,
                preemptible,
                needsHands,
                cancelled,
                satisfied);
    }

    /**
     * How urgently, for how long and how firmly a request wants its board, and whether it needs
     * somebody in the lab.
     *
     * <p>The standard terms are priority {@value Lease#DEFAULT_PRIORITY}, {@value
     * Request#DEFAULT_MINUTES} minutes, preemptible, and no hands needed. Terms are checked when a
     * request is made from them.
     *
     * @param priority how urgent, one being the most
     * @param minutes how long the board is wanted for
     * @param preemptible whether the lease may be taken away by something more urgent
     * @param needsHands whether the job needs somebody in the lab
     */
    public record Terms(int priority, int minutes, boolean preemptible, boolean needsHands) {

        /** The terms a request is on unless it says otherwise. */
        public static Terms standard() {
            return new Terms(Lease.DEFAULT_PRIORITY, DEFAULT_MINUTES, true, false);
        }

        public Terms withPriority(int value) {
            return new Terms(value, minutes, preemptible, needsHands);
        }

        public Terms withMinutes(int value) {
            return new Terms(priority, value, preemptible, needsHands);
        }

        public Terms withPreemptible(boolean value) {
            return new Terms(priority, minutes, value, needsHands);
        }

        public Terms withNeedsHands(boolean value) {
            return new Terms(priority, minutes, preemptible, value);
        }
    }
}
