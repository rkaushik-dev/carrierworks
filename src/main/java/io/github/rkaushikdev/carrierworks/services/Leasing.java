package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Conflict;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Taking a board, giving it back, and keeping it a bit longer.
 *
 * <p>The lease and the board state are two rows that have to agree: a leased board says leased, an
 * available one has no live lease. Every operation here writes both, in one unit of work, which is
 * the whole reason the unit of work exists.
 *
 * <p>Lease numbers are derived from what is already stored rather than from a counter in memory. A
 * restart in the middle of a busy afternoon would otherwise start again at one and collide with
 * everything issued that morning.
 */
public final class Leasing {

    /** How far ahead a lease counts as running out, unless the caller says otherwise. */
    public static final Duration WARNING_WINDOW = Duration.ofMinutes(15);

    private static final String PREFIX = "lease";
    private static final int WIDTH = 5;
    private static final String SWEEPER = "sweeper";

    private Leasing() {}

    /** The next lease number, carried on from whatever is in the database: {@code lease-00001}. */
    public static String nextLeaseId(UnitOfWork unit) {
        return Numbering.next(
                PREFIX,
                WIDTH,
                unit.boards().all().stream()
                        .flatMap(board -> unit.leases().history(board.tag()).stream())
                        .map(Lease::id));
    }

    /** Take a board on the standard terms and the default settings. */
    public static Lease acquire(UnitOfWork unit, String tag, String holder, String purpose, Instant now) {
        return acquire(unit, tag, holder, purpose, now, LeaseTerms.standard(), BenchSettings.defaults());
    }

    /** Take a board for some minutes, on the default settings. */
    public static Lease acquire(UnitOfWork unit, String tag, String holder, String purpose, Instant now, int minutes) {
        return acquire(
                unit, tag, holder, purpose, now, LeaseTerms.standard().withMinutes(minutes), BenchSettings.defaults());
    }

    /**
     * Take a board, refusing if it is busy or out of service.
     *
     * <p>The lease is checked before the state so that a board somebody else has says who has it.
     * Reporting only that it is not available is true and sends the asker to look at the rack for
     * no reason.
     *
     * @throws ValidationError when the length is not positive or runs past the site's ceiling
     * @throws Conflict when somebody holds the board already, or it is not in service
     */
    public static Lease acquire(
            UnitOfWork unit,
            String tag,
            String holder,
            String purpose,
            Instant now,
            LeaseTerms terms,
            BenchSettings settings) {
        int length = terms.minutes() == 0 ? settings.defaultLeaseMinutes() : terms.minutes();
        if (length <= 0) {
            throw new ValidationError("a lease lasts some minutes", "minutes", Details.of("given", length));
        }
        if (length > settings.maxLeaseMinutes()) {
            throw new ValidationError(
                    "that is longer than a lease may run",
                    "minutes",
                    Details.of("given", length, "ceiling", settings.maxLeaseMinutes()));
        }
        Optional<Lease> held = unit.leases().forBoard(tag, now);
        if (held.isPresent()) {
            throw new Conflict(
                    "that board is already leased",
                    Details.of("board", tag, "holder", held.get().holder()));
        }
        BoardState state = unit.boards().state(tag);
        if (!state.usable()) {
            throw new Conflict(
                    "that board is not available",
                    Details.of("board", tag, "state", state.state().value()));
        }
        Lease lease = Lease.builder(nextLeaseId(unit), tag, holder, purpose, now, now.plus(Duration.ofMinutes(length)))
                .priority(terms.priority())
                .preemptible(terms.preemptible())
                .build();
        unit.leases().add(lease);
        unit.boards().saveState(tag, state.move(BoardStatus.LEASED, now, "", lease.holder()));
        Audit.record(unit, now, "lease-taken", lease.holder(), lease.board(), lease.id() + " for " + lease.purpose());
        return lease;
    }

    /** {@link #release(UnitOfWork, String, Instant, String)} giving the plain reason. */
    public static Lease release(UnitOfWork unit, String leaseId, Instant now) {
        return release(unit, leaseId, now, Lease.RELEASED);
    }

    /**
     * Give a board back and put it into service again.
     *
     * <p>A board still being programmed comes back through leased, which is the only way out of
     * programming the state machine allows.
     */
    public static Lease release(UnitOfWork unit, String leaseId, Instant now, String reason) {
        Lease lease = unit.leases().get(leaseId);
        Lease closed = lease.release(now, reason);
        unit.leases().save(closed);
        BoardState state = unit.boards().state(lease.board());
        if (state.state() == BoardStatus.PROGRAMMING) {
            state = state.move(BoardStatus.LEASED, now, "", lease.holder());
            unit.boards().saveState(lease.board(), state);
        }
        if (state.state() == BoardStatus.LEASED) {
            unit.boards().saveState(lease.board(), state.move(BoardStatus.AVAILABLE, now, "", lease.holder()));
        }
        Audit.record(unit, now, "lease-released", lease.holder(), lease.board(), lease.id() + " " + reason);
        return closed;
    }

    /** {@link #renew(UnitOfWork, String, Instant, int, BenchSettings)} under the default settings. */
    public static Lease renew(UnitOfWork unit, String leaseId, Instant now, int minutes) {
        return renew(unit, leaseId, now, minutes, BenchSettings.defaults());
    }

    /** Push a lease out, up to the site's ceiling measured from when it started. */
    public static Lease renew(UnitOfWork unit, String leaseId, Instant now, int minutes, BenchSettings settings) {
        Lease renewed = unit.leases().get(leaseId).renewed(now, minutes, settings.maxLeaseMinutes());
        unit.leases().save(renewed);
        return renewed;
    }

    /**
     * Close every lease that has run out and free the boards they held.
     *
     * <p>Nothing runs this on a timer. The dispatcher calls it at the top of every pass, which is
     * often enough that a board is never held by a dead job for longer than one round.
     *
     * @return the leases closed, board by board in tag order and oldest first on each board
     */
    public static List<Lease> sweepExpired(UnitOfWork unit, Instant now) {
        List<Lease> closed = new ArrayList<>();
        for (Board board : unit.boards().all()) {
            for (Lease lease : unit.leases().history(board.tag())) {
                if (lease.released() || !lease.expired(now)) {
                    continue;
                }
                Lease expired = lease.expire();
                unit.leases().save(expired);
                BoardState state = unit.boards().state(board.tag());
                if (state.state() == BoardStatus.LEASED || state.state() == BoardStatus.PROGRAMMING) {
                    if (state.state() == BoardStatus.PROGRAMMING) {
                        state = state.move(BoardStatus.LEASED, now, "", SWEEPER);
                        unit.boards().saveState(board.tag(), state);
                    }
                    unit.boards().saveState(board.tag(), state.move(BoardStatus.AVAILABLE, now, "", SWEEPER));
                }
                closed.add(expired);
            }
        }
        return List.copyOf(closed);
    }

    /** Leases that run out within {@link #WARNING_WINDOW}, soonest first. */
    public static List<Lease> expiring(UnitOfWork unit, Instant now) {
        return expiring(unit, now, WARNING_WINDOW);
    }

    /**
     * Leases that run out soon, soonest first, and by id where two run out together.
     *
     * <p>Soon is a window rather than a threshold because the interesting question is who to warn,
     * and warning somebody about a lease with four hours left is how people learn to ignore the
     * warnings.
     *
     * @throws ValidationError when the window has no length
     */
    public static List<Lease> expiring(UnitOfWork unit, Instant now, Duration within) {
        if (!within.isPositive()) {
            throw new ValidationError("a warning window has some length", "within");
        }
        return unit.leases().active(now).stream()
                .filter(lease -> lease.remaining(now).compareTo(within) <= 0)
                .sorted(Comparator.comparing(Lease::expiresAt).thenComparing(Lease::id))
                .toList();
    }

    /** {@link #remind(UnitOfWork, Instant, Duration)} over {@link #WARNING_WINDOW}. */
    public static List<Lease> remind(UnitOfWork unit, Instant now) {
        return remind(unit, now, WARNING_WINDOW);
    }

    /**
     * Write a warning into the trail for every lease about to run out, and hand those leases back.
     *
     * <p>The trail is the only place the bench can leave a message for somebody who is not looking
     * at it right now, and a swept lease that surprised its holder is the complaint this answers.
     * The minutes left are rounded down.
     */
    public static List<Lease> remind(UnitOfWork unit, Instant now, Duration within) {
        List<Lease> warned = expiring(unit, now, within);
        for (Lease lease : warned) {
            long minutes = Math.floorDiv(lease.remaining(now).getSeconds(), 60);
            Audit.record(
                    unit,
                    now,
                    "expiring",
                    Audit.BENCH,
                    lease.board(),
                    lease.id() + " held by " + lease.holder() + " runs out in " + minutes + " minutes");
        }
        return warned;
    }
}
