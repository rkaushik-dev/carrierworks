package io.github.rkaushikdev.carrierworks;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The helpers four books had each grown their own copy of.
 *
 * <p>Faults, maintenance, thermal samples and wear counts are four different things with four
 * different rules, and underneath all of them was the same container: entries keyed by a
 * normalised asset tag, and a windowed query that filters on a timestamp. The container is {@link
 * PerBoard}; the tag handling, the window and the tally are here. Nothing in this class knows what a
 * fault or a sample is.
 */
public final class Records {

    private Records() {}

    /** One normalised asset tag, however it was written. */
    public static String boardKey(String value) {
        return AssetTag.parse(value).toString();
    }

    /** Everything whose timestamp falls in a window ending now, ends included, in order. */
    public static <T> List<T> inWindow(
            Collection<? extends T> items, Instant now, Duration window, Function<? super T, Instant> at) {
        if (window.isNegative() || window.isZero()) {
            throw new ValidationError("a window has some length", "window");
        }
        Instant start = now.minus(window);
        return items.stream()
                .filter(item -> {
                    Instant moment = at.apply(item);
                    return !moment.isBefore(start) && !moment.isAfter(now);
                })
                .<T>map(item -> item)
                .toList();
    }

    /** How many times each name appears, in the order the names were first seen. */
    public static Map<String, Integer> tally(Iterable<String> names) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String name : names) {
            counts.merge(name, 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    /** A tally as the line that goes in a status message: {@code 2 available, 1 faulted}. */
    public static String describeTally(Map<String, Integer> counts) {
        return describeTally(counts, "nothing");
    }

    /** The same, saying {@code empty} when there is nothing to count. */
    public static String describeTally(Map<String, Integer> counts, String empty) {
        if (counts.isEmpty()) {
            return empty;
        }
        return new TreeMap<>(counts)
                .entrySet().stream()
                        .map(entry -> entry.getValue() + " " + entry.getKey())
                        .collect(Collectors.joining(", "));
    }
}
