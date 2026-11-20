package io.github.rkaushikdev.carrierworks.scheduling;

import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.util.Numbers;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the bench was doing, measured over a window.
 *
 * <p>Three questions get asked and they are asked by different people. The lab manager wants
 * utilisation, because a bench at thirty percent does not need the two boards somebody is asking to
 * buy. The people waiting want to know how long the queue has been, because a median wait of four
 * minutes and a median wait of forty are different labs. And when the argument starts about who is
 * hogging what, board hours by holder settles it in one line.
 *
 * <p>Everything is computed over a window with both ends, and a lease that started before the window
 * or runs past the end of it is clipped rather than dropped. Dropping them is how a busy weekend
 * reads as an idle one. A lease still held counts up to its expiry.
 *
 * <p>Every method that takes a window refuses one that does not end after it starts.
 */
public final class Metrics {

    private static final double SECONDS_PER_HOUR = 3600;
    private static final int HOUR_PLACES = 2;
    private static final BigDecimal NANOS_PER_TWO_MICROS = BigDecimal.valueOf(2_000);

    private Metrics() {}

    /**
     * How long each board was held inside the window, in seconds.
     *
     * <p>Boards appear in the order their first lease is listed, and a board held for no time at all
     * inside the window is left out.
     */
    public static Map<String, Double> busySeconds(List<Lease> leases, Instant start, Instant end) {
        window(start, end);
        Map<String, Double> busy = new LinkedHashMap<>();
        for (Lease lease : leases) {
            double held = overlap(lease, start, end);
            if (held > 0) {
                busy.merge(lease.board(), held, Double::sum);
            }
        }
        return Collections.unmodifiableMap(busy);
    }

    /**
     * The fraction of the window each board spent leased, board by board, in tag order.
     *
     * <p>Boards that were never leased are included at zero, because a report that silently leaves
     * out the idle boards answers the opposite of the question it was asked. Overlapping leases on
     * one board never take it past one.
     */
    public static Map<String, Double> utilisation(
            List<Lease> leases, Collection<String> boards, Instant start, Instant end) {
        double span = window(start, end);
        Map<String, Double> busy = busySeconds(leases, start, end);
        Map<String, Double> shares = new LinkedHashMap<>();
        for (String board : new TreeSet<>(boards)) {
            shares.put(board, Math.min(busy.getOrDefault(board, 0.0) / span, 1.0));
        }
        return Collections.unmodifiableMap(shares);
    }

    /** Board hours by holder, in holder order, rounded to two places for reading. */
    public static Map<String, Double> holderHours(List<Lease> leases, Instant start, Instant end) {
        window(start, end);
        Map<String, Double> hours = new TreeMap<>();
        for (Lease lease : leases) {
            double held = overlap(lease, start, end);
            if (held > 0) {
                hours.merge(lease.holder(), held / SECONDS_PER_HOUR, Double::sum);
            }
        }
        Map<String, Double> rounded = new LinkedHashMap<>();
        hours.forEach((holder, value) -> rounded.put(holder, Numbers.round(value, HOUR_PLACES)));
        return Collections.unmodifiableMap(rounded);
    }

    /** The queue as three numbers, counting only the requests still waiting. */
    public static WaitStats waitStats(RequestQueue queue, Instant now) {
        List<Duration> waits = queue.pending().stream()
                .map(request -> request.waited(now))
                .sorted()
                .toList();
        if (waits.isEmpty()) {
            return WaitStats.none();
        }
        int middle = waits.size() / 2;
        Duration median =
                waits.size() % 2 == 1 ? waits.get(middle) : midpoint(waits.get(middle - 1), waits.get(middle));
        return new WaitStats(waits.size(), waits.getLast(), median);
    }

    /** Boards nobody held at all in the window, in tag order, which is the buy or sell list. */
    public static List<String> idleBoards(List<Lease> leases, Collection<String> boards, Instant start, Instant end) {
        Map<String, Double> busy = busySeconds(leases, start, end);
        return new TreeSet<>(boards)
                .stream().filter(board -> !busy.containsKey(board)).toList();
    }

    private static double window(Instant start, Instant end) {
        if (!end.isAfter(start)) {
            throw new ValidationError("a window ends after it starts", "window");
        }
        return Times.totalSeconds(Duration.between(start, end));
    }

    /** Seconds this lease held its board inside the window. */
    private static double overlap(Lease lease, Instant start, Instant end) {
        Instant began = lease.takenAt().isAfter(start) ? lease.takenAt() : start;
        Instant stopped = lease.releasedAt().orElse(lease.expiresAt());
        Instant finished = stopped.isBefore(end) ? stopped : end;
        return Math.max(Times.totalSeconds(Duration.between(began, finished)), 0.0);
    }

    /** Halfway between two waits, to the microsecond the bench keeps time in, a half going to even. */
    private static Duration midpoint(Duration low, Duration high) {
        BigDecimal micros =
                BigDecimal.valueOf(low.plus(high).toNanos()).divide(NANOS_PER_TWO_MICROS, 0, RoundingMode.HALF_EVEN);
        return Duration.of(micros.longValueExact(), ChronoUnit.MICROS);
    }
}
