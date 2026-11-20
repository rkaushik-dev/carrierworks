package io.github.rkaushikdev.carrierworks.leases;

import io.github.rkaushikdev.carrierworks.AssetTag;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * One hold on one board: who, why, until when, and how firmly.
 *
 * <p>A lease is the only thing standing between two people programming the same board at the same
 * time, which is why it is a record with an expiry rather than a flag somebody remembers to clear.
 * The regression that crashes at two in the morning does not release anything; the expiry does it
 * instead.
 *
 * <p>Everything about a lease is a compromise between two failure modes. Too short and a nightly
 * regression loses its board halfway through a run because somebody set the default to twenty
 * minutes. Too long and a board sits idle all weekend because a job crashed on Friday afternoon
 * holding it. So a lease expires, it can be renewed while its holder is still alive, and there is a
 * ceiling on how far renewal can push it. A job that has held a board for eight hours is not a job
 * any more, it is a leak.
 *
 * <p>Priority and preemptibility are separate on purpose. Priority decides who gets a free board
 * first. Preemptibility decides whether a held board can be taken away, and a low priority job
 * that is halfway through a two hour capture is still not worth interrupting.
 *
 * <p>A lease is immutable: renewing, releasing and expiring one hand back a new lease.
 *
 * @param id the lease id, as a slug, such as {@code lease-00001}
 * @param board the asset tag of the board held, normalised
 * @param holder who holds it, as a slug
 * @param purpose what the board is held for, never blank
 * @param takenAt when the lease was taken
 * @param expiresAt when it runs out, always after it was taken
 * @param priority {@value #MIN_PRIORITY} is the most urgent, {@value #MAX_PRIORITY} the least
 * @param renewals how many times it has been renewed
 * @param preemptible whether a more urgent request may take the board away
 * @param releasedAt when it was released, if it has been
 * @param releaseReason why it was released, or empty while it is held
 */
public record Lease(
        String id,
        String board,
        String holder,
        String purpose,
        Instant takenAt,
        Instant expiresAt,
        int priority,
        int renewals,
        boolean preemptible,
        Optional<Instant> releasedAt,
        String releaseReason) {

    /**
     * The most urgent priority. The scale is small because anything wider turns into everybody
     * picking two.
     */
    public static final int MIN_PRIORITY = 1;

    /** The least urgent priority. */
    public static final int MAX_PRIORITY = 9;

    /** The priority a lease has unless somebody says otherwise. */
    public static final int DEFAULT_PRIORITY = 5;

    /** How far past its start renewal may push a lease unless the caller says otherwise. */
    public static final int DEFAULT_CEILING_MINUTES = 480;

    /** The reason a lease records when it is released without one. */
    public static final String RELEASED = "released";

    /** The reason a lease records when it ran out rather than being released. */
    public static final String EXPIRED = "expired";

    public Lease {
        id = Ids.slug(id);
        board = AssetTag.parse(board).toString();
        holder = Ids.slug(holder);
        if (purpose.isBlank()) {
            throw new ValidationError("a lease says what it is for", "purpose");
        }
        if (!expiresAt.isAfter(takenAt)) {
            throw new ValidationError("a lease expires after it is taken", "expires_at");
        }
        if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
            throw new ValidationError("priority is one to nine", "priority", Details.of("given", priority));
        }
        if (renewals < 0) {
            throw new ValidationError("renewals are counted up", "renewals");
        }
        if (releasedAt.isPresent() && releasedAt.get().isBefore(takenAt)) {
            throw new ValidationError("a lease cannot be released before it was taken", "lease");
        }
    }

    /** A fresh lease at the default priority, preemptible and never renewed. */
    public Lease(String id, String board, String holder, String purpose, Instant takenAt, Instant expiresAt) {
        this(id, board, holder, purpose, takenAt, expiresAt, DEFAULT_PRIORITY, 0, true, Optional.empty(), "");
    }

    /** A lease assembled a field at a time, starting from everything a lease cannot do without. */
    public static Builder builder(
            String id, String board, String holder, String purpose, Instant takenAt, Instant expiresAt) {
        return new Builder(id, board, holder, purpose, takenAt, expiresAt);
    }

    /** A builder holding everything this lease holds, to change one or two things. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Whether the lease has been closed, by its holder or by running out. */
    public boolean released() {
        return releasedAt.isPresent();
    }

    /** Whether the lease has run out by {@code now}, released or not. */
    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Held right now, which means not released and not run out. */
    public boolean active(Instant now) {
        return !released() && !expired(now);
    }

    /** How long the lease has left, or nothing once it is no longer active. */
    public Duration remaining(Instant now) {
        if (!active(now)) {
            return Duration.ZERO;
        }
        return Duration.between(now, expiresAt);
    }

    /**
     * How long the board has been held: up to the release if there was one, otherwise up to now but
     * never past the expiry.
     */
    public Duration heldFor(Instant now) {
        Instant end = releasedAt.orElseGet(() -> now.isBefore(expiresAt) ? now : expiresAt);
        Duration held = Duration.between(takenAt, end);
        return held.isNegative() ? Duration.ZERO : held;
    }

    /** From taking to expiry, as the lease stands. */
    public Duration totalLength() {
        return Duration.between(takenAt, expiresAt);
    }

    /** {@link #renewed(Instant, int, int)} under the default ceiling. */
    public Lease renewed(Instant now, int minutes) {
        return renewed(now, minutes, DEFAULT_CEILING_MINUTES);
    }

    /**
     * The lease with its expiry pushed out, up to the ceiling measured from the start.
     *
     * <p>Renewal happens from now rather than from the old expiry, because a job that renews late
     * has been running all that time and the point of the ceiling is to bound how long a board can
     * be held. A lease already past its ceiling cannot be renewed at all.
     *
     * @throws ValidationError when the lease is released, the minutes are not positive, or the
     *     ceiling has passed
     */
    public Lease renewed(Instant now, int minutes, int ceilingMinutes) {
        if (released()) {
            throw new ValidationError("a released lease cannot be renewed", "lease", Details.of("id", id));
        }
        if (minutes <= 0) {
            throw new ValidationError("renew for some minutes", "minutes", Details.of("given", minutes));
        }
        Instant wanted = now.plus(Duration.ofMinutes(minutes));
        Instant ceiling = takenAt.plus(Duration.ofMinutes(ceilingMinutes));
        if (now.isAfter(ceiling)) {
            throw new ValidationError(
                    "this lease has reached its ceiling",
                    "lease",
                    Details.of("id", id, "ceiling_minutes", ceilingMinutes));
        }
        return toBuilder()
                .expiresAt(wanted.isBefore(ceiling) ? wanted : ceiling)
                .renewals(renewals + 1)
                .build();
    }

    /** {@link #release(Instant, String)} giving the plain reason. */
    public Lease release(Instant now) {
        return release(now, RELEASED);
    }

    /**
     * The lease closed at {@code now}.
     *
     * @throws ValidationError when it is already released
     */
    public Lease release(Instant now, String reason) {
        if (released()) {
            throw new ValidationError("that lease is already released", "lease", Details.of("id", id));
        }
        return toBuilder().releasedAt(now).releaseReason(reason).build();
    }

    /**
     * Close a lease that ran out, keeping its expiry as the release time.
     *
     * <p>A lease that is already released comes back unchanged.
     */
    public Lease expire() {
        if (released()) {
            return this;
        }
        return toBuilder().releasedAt(expiresAt).releaseReason(EXPIRED).build();
    }

    /** Whether this lease's holder would win a contest for a board. */
    public boolean outranks(Lease other) {
        return priority < other.priority;
    }

    /**
     * {@code lease-00001 bx-14 held by signal-processing for capture, until 2025-08-01T10:00+00:00},
     * or {@code ..., released (job finished)} once it is closed.
     */
    public String describe() {
        String line = id + " " + board + " held by " + holder + " for " + purpose;
        if (released()) {
            return line + ", released (" + releaseReason + ")";
        }
        return line + ", until " + Times.iso(expiresAt, Times.Precision.MINUTES);
    }

    /** A lease assembled a field at a time and checked once, on {@link #build()}. */
    public static final class Builder {

        private final String id;
        private final String board;
        private final String holder;
        private String purpose;
        private Instant takenAt;
        private Instant expiresAt;
        private int priority = DEFAULT_PRIORITY;
        private int renewals;
        private boolean preemptible = true;
        private Optional<Instant> releasedAt = Optional.empty();
        private String releaseReason = "";

        private Builder(String id, String board, String holder, String purpose, Instant takenAt, Instant expiresAt) {
            this.id = id;
            this.board = board;
            this.holder = holder;
            this.purpose = purpose;
            this.takenAt = takenAt;
            this.expiresAt = expiresAt;
        }

        private Builder(Lease from) {
            this(from.id, from.board, from.holder, from.purpose, from.takenAt, from.expiresAt);
            priority = from.priority;
            renewals = from.renewals;
            preemptible = from.preemptible;
            releasedAt = from.releasedAt;
            releaseReason = from.releaseReason;
        }

        public Builder purpose(String value) {
            purpose = value;
            return this;
        }

        public Builder takenAt(Instant value) {
            takenAt = value;
            return this;
        }

        public Builder expiresAt(Instant value) {
            expiresAt = value;
            return this;
        }

        public Builder priority(int value) {
            priority = value;
            return this;
        }

        public Builder renewals(int value) {
            renewals = value;
            return this;
        }

        public Builder preemptible(boolean value) {
            preemptible = value;
            return this;
        }

        /** When the lease was closed. */
        public Builder releasedAt(Instant value) {
            releasedAt = Optional.of(value);
            return this;
        }

        public Builder releaseReason(String value) {
            releaseReason = value;
            return this;
        }

        /** The lease, checked. */
        public Lease build() {
            return new Lease(
                    id,
                    board,
                    holder,
                    purpose,
                    takenAt,
                    expiresAt,
                    priority,
                    renewals,
                    preemptible,
                    releasedAt,
                    releaseReason);
        }
    }
}
