package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.util.Numbers;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the bench did over one window.
 *
 * @param start when the window opened
 * @param end when it closed
 * @param boards every board on the bench, in tag order
 * @param utilisation the fraction of the window each board spent leased, in tag order, idle boards
 *     included at zero
 * @param holderHours board hours by holder, in holder order
 * @param idle the boards nobody held at all, in tag order
 * @param leases how many leases overlapped the window
 * @param waiting how many requests are waiting now
 */
public record UsageReport(
        Instant start,
        Instant end,
        List<String> boards,
        Map<String, Double> utilisation,
        Map<String, Double> holderHours,
        List<String> idle,
        int leases,
        int waiting) {

    private static final double SECONDS_PER_HOUR = 3600;
    private static final double HOURS_PER_DAY = 24;

    public UsageReport {
        boards = List.copyOf(boards);
        utilisation = Collections.unmodifiableMap(new LinkedHashMap<>(utilisation));
        holderHours = Collections.unmodifiableMap(new LinkedHashMap<>(holderHours));
        idle = List.copyOf(idle);
    }

    /** How long the window was, in hours. */
    public double hours() {
        return Times.totalSeconds(Duration.between(start, end)) / SECONDS_PER_HOUR;
    }

    /**
     * The board held longest, the first in tag order on a tie, or nothing when none of them were
     * held.
     *
     * <p>A bench where nothing ran has no busiest board, and answering with whichever board sorted
     * first would read as a real number to anybody skimming the report.
     */
    public Optional<String> busiest() {
        Optional<Map.Entry<String, Double>> most = Optional.empty();
        for (Map.Entry<String, Double> entry : utilisation.entrySet()) {
            if (most.isEmpty() || entry.getValue() > most.get().getValue()) {
                most = Optional.of(entry);
            }
        }
        return most.filter(entry -> entry.getValue() > 0).map(Map.Entry::getKey);
    }

    /** {@code 2 boards over 7.0 days, 1 leases, busiest bx-14 at 1%, idle: bx-15}. */
    public String describe() {
        String days = Numbers.shortest(Numbers.round(hours() / HOURS_PER_DAY, 1));
        String line = boards.size() + " boards over " + days + " days, " + leases + " leases";
        Optional<String> most = busiest();
        if (most.isPresent()) {
            line = line + ", busiest " + most.get() + " at " + Numbers.fixed(utilisation.get(most.get()) * 100, 0)
                    + "%";
        }
        if (!idle.isEmpty()) {
            line = line + ", idle: " + String.join(", ", idle);
        }
        return line;
    }
}
