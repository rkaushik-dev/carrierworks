package io.github.rkaushikdev.carrierworks.leases;

import io.github.rkaushikdev.carrierworks.AssetTag;
import io.github.rkaushikdev.carrierworks.Conflict;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.IdFactory;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.LeaseRegistry;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Every lease the bench has issued, live and closed, and the one rule that makes them worth having.
 *
 * <p>One active lease per board. Everything else here is bookkeeping around that single invariant,
 * and every method that could break it checks the same way, by asking for the board's current
 * lease rather than by trusting an index.
 *
 * <p>Expiry is a sweep rather than a background clock. Nothing in the bench runs continuously
 * except the supervisor, so leases go stale silently and are collected when somebody next asks.
 * That means every read treats an expired lease as gone, which the lease itself already does, and
 * {@link #sweep(Instant)} only exists to write the closure down.
 *
 * <p>Listings come back oldest first: by when the lease was taken, then by id.
 */
public final class LeaseStore implements LeaseRegistry {

    /** How long a lease lasts when the caller does not say. */
    public static final int DEFAULT_MINUTES = 60;

    private static final Comparator<Lease> TAKEN_ORDER =
            Comparator.comparing(Lease::takenAt).thenComparing(Lease::id);

    private final int ceilingMinutes;
    private final Map<String, Lease> leases = new LinkedHashMap<>();
    private final IdFactory ids;

    /** A store under the default ceiling, issuing {@code lease-00001} onwards. */
    public LeaseStore() {
        this(Lease.DEFAULT_CEILING_MINUTES);
    }

    /** A store under this ceiling, issuing {@code lease-00001} onwards. */
    public LeaseStore(int ceilingMinutes) {
        this(ceilingMinutes, 1);
    }

    /**
     * A store under this ceiling whose ids carry on from {@code start}, so a restart does not replay
     * ids that already exist.
     *
     * @throws ValidationError when the ceiling is not a positive number of minutes
     */
    public LeaseStore(int ceilingMinutes, long start) {
        if (ceilingMinutes <= 0) {
            throw new ValidationError("a lease ceiling is some minutes", "ceiling_minutes");
        }
        this.ceilingMinutes = ceilingMinutes;
        this.ids = new IdFactory("lease", start);
    }

    /** The longest a lease may run, and the furthest renewal may push one from its start. */
    public int ceilingMinutes() {
        return ceilingMinutes;
    }

    /** {@link #acquire(String, String, String, Instant, int, int, boolean)} for the default hour. */
    public Lease acquire(String board, String holder, String purpose, Instant now) {
        return acquire(board, holder, purpose, now, DEFAULT_MINUTES);
    }

    /** A preemptible lease at the default priority. */
    public Lease acquire(String board, String holder, String purpose, Instant now, int minutes) {
        return acquire(board, holder, purpose, now, minutes, Lease.DEFAULT_PRIORITY, true);
    }

    /**
     * Take a board, or refuse because somebody else has it.
     *
     * @throws ValidationError when the minutes are not positive or run past the ceiling
     * @throws Conflict when the board is already held, naming the holder, the lease and when it runs
     *     out
     */
    public Lease acquire(
            String board, String holder, String purpose, Instant now, int minutes, int priority, boolean preemptible) {
        if (minutes <= 0) {
            throw new ValidationError("a lease lasts some minutes", "minutes", Details.of("given", minutes));
        }
        if (minutes > ceilingMinutes) {
            throw new ValidationError(
                    "that is longer than a lease may run",
                    "minutes",
                    Details.of("given", minutes, "ceiling", ceilingMinutes));
        }
        String tag = AssetTag.parse(board).toString();
        Optional<Lease> held = forBoard(tag, now);
        if (held.isPresent()) {
            throw new Conflict(
                    "that board is already leased",
                    Details.of(
                            "board",
                            tag,
                            "holder",
                            held.get().holder(),
                            "lease",
                            held.get().id(),
                            "until",
                            Times.iso(held.get().expiresAt(), Times.Precision.MINUTES)));
        }
        Lease lease = Lease.builder(ids.next(), tag, holder, purpose, now, now.plus(Duration.ofMinutes(minutes)))
                .priority(priority)
                .preemptible(preemptible)
                .build();
        leases.put(lease.id(), lease);
        return lease;
    }

    /** One lease by id, or a not found. */
    public Lease lease(String leaseId) {
        String key = Ids.slug(leaseId);
        Lease found = leases.get(key);
        if (found == null) {
            throw new NotFound("lease", key);
        }
        return found;
    }

    /** Close a lease with the plain reason. */
    @Override
    public Lease release(String leaseId, Instant now) {
        return release(leaseId, now, Lease.RELEASED);
    }

    /** Close a lease and keep the closed form. */
    @Override
    public Lease release(String leaseId, Instant now, String reason) {
        Lease closed = lease(leaseId).release(now, reason);
        leases.put(closed.id(), closed);
        return closed;
    }

    /** Push a lease's expiry out, never past this store's ceiling. */
    public Lease renew(String leaseId, Instant now, int minutes) {
        Lease renewed = lease(leaseId).renewed(now, minutes, ceilingMinutes);
        leases.put(renewed.id(), renewed);
        return renewed;
    }

    /** The lease holding this board right now, if there is one. */
    @Override
    public Optional<Lease> forBoard(String board, Instant now) {
        String tag = AssetTag.parse(board).toString();
        return leases.values().stream()
                .filter(lease -> lease.board().equals(tag) && lease.active(now))
                .findFirst();
    }

    /** What one holder is sitting on right now, oldest first. */
    public List<Lease> heldBy(String holder, Instant now) {
        String wanted = Ids.slug(holder);
        return sorted().stream()
                .filter(lease -> lease.holder().equals(wanted) && lease.active(now))
                .toList();
    }

    /** Every lease holding a board right now, oldest first. */
    public List<Lease> active(Instant now) {
        return sorted().stream().filter(lease -> lease.active(now)).toList();
    }

    /** Every lease ever issued, oldest first. */
    public List<Lease> all() {
        return sorted();
    }

    /** Every lease ever taken on one board, closed ones included, oldest first. */
    public List<Lease> history(String board) {
        String tag = AssetTag.parse(board).toString();
        return sorted().stream().filter(lease -> lease.board().equals(tag)).toList();
    }

    /** Close every lease that has run out, and say which those were. */
    public List<Lease> sweep(Instant now) {
        List<Lease> closed = new ArrayList<>();
        for (Lease lease : sorted()) {
            if (!lease.released() && lease.expired(now)) {
                Lease expired = lease.expire();
                leases.put(expired.id(), expired);
                closed.add(expired);
            }
        }
        return List.copyOf(closed);
    }

    /** The tags of every board held right now, sorted. */
    public List<String> boardsHeld(Instant now) {
        return List.copyOf(new TreeSet<>(active(now).stream().map(Lease::board).toList()));
    }

    /**
     * How many boards each holder has right now, which is what a quota reads.
     *
     * <p>Holders appear in the order their oldest active lease was taken.
     */
    public Map<String, Integer> countsByHolder(Instant now) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Lease lease : active(now)) {
            counts.merge(lease.holder(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    /** How many leases have been issued, live and closed. */
    public int size() {
        return leases.size();
    }

    private List<Lease> sorted() {
        return leases.values().stream().sorted(TAKEN_ORDER).toList();
    }
}
