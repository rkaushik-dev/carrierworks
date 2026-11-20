package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.scheduling.Metrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Who used what, for how long, and which boards nobody wanted.
 *
 * <p>The numbers here settle two arguments that come up every quarter. One team thinks another is
 * monopolising the Kintex boards, and the lab is asked to justify keeping hardware that might be
 * idle. Both are answerable from the leases already stored, and neither is answerable from memory.
 *
 * <p>The window is closed at both ends and every lease that overlaps it counts for the part that
 * overlaps, so a lease that ran across midnight is not counted twice and not dropped. That is the
 * only arithmetic in here that is easy to get wrong, and it lives in {@link Metrics} rather than
 * here.
 */
public final class Usage {

    /** How far back a usage report looks unless it is told otherwise. */
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(7);

    private Usage() {}

    /**
     * Every lease that overlapped the window, live ones included, board by board in tag order.
     *
     * <p>Read per board rather than by date, because the lease history is indexed by board and a
     * window query would scan the whole table for what is nearly always a handful of rows.
     */
    public static List<Lease> leasesIn(UnitOfWork unit, Instant start, Instant end) {
        return unit.boards().all().stream()
                .flatMap(board -> unit.leases().history(board.tag()).stream())
                .filter(lease -> lease.takenAt().isBefore(end)
                        && lease.releasedAt().orElse(lease.expiresAt()).isAfter(start))
                .toList();
    }

    /** {@link #usage(UnitOfWork, Instant, Duration)} over the last week. */
    public static UsageReport usage(UnitOfWork unit, Instant now) {
        return usage(unit, now, DEFAULT_WINDOW);
    }

    /**
     * Utilisation, board hours by team, and the boards nobody touched, over a window ending now.
     *
     * @throws ValidationError when the window has no length
     */
    public static UsageReport usage(UnitOfWork unit, Instant now, Duration window) {
        if (!window.isPositive()) {
            throw new ValidationError("a usage window has some length", "window");
        }
        Instant start = now.minus(window);
        List<String> boards = unit.boards().all().stream().map(Board::tag).toList();
        List<Lease> leases = leasesIn(unit, start, now);
        return new UsageReport(
                start,
                now,
                boards,
                Metrics.utilisation(leases, boards, start, now),
                Metrics.holderHours(leases, start, now),
                Metrics.idleBoards(leases, boards, start, now),
                leases.size(),
                unit.requests().open().size());
    }
}
