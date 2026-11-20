package io.github.rkaushikdev.carrierworks;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a thing physically is, and what that means for the things near it.
 *
 * <p>The part that is not bookkeeping is airflow: the racks pull air from the bottom, so a board
 * running hot warms the slots above it and nothing below it. The thermal supervisor asks this
 * class which boards are downwind of a hot one, and it gets a different answer than it would from a
 * plain distance.
 */
public final class Locations {

    /** Shelves in one rack, numbered from the floor up. */
    public static final int SHELVES_PER_RACK = 12;

    /** Slots on one shelf, numbered left to right as you face the rack. */
    public static final int SLOTS_PER_SHELF = 6;

    /** How many shelves up the warm air from a hot board is worth warning about. */
    public static final int DEFAULT_REACH = 2;

    private Locations() {}

    /** {@link #downwindOf(BenchLocation, Map, int)} with the usual two shelves of reach. */
    public static List<String> downwindOf(BenchLocation source, Map<String, BenchLocation> candidates) {
        return downwindOf(source, candidates, DEFAULT_REACH);
    }

    /**
     * Which of {@code candidates} sit in the warm air coming off {@code source}.
     *
     * <p>Same rack, same slot column, and within {@code reach} shelves above. Returns the keys in
     * the order the air reaches them, nearest first, which is also the order the supervisor wants to
     * warn people in. Two keys at one location come back in key order.
     */
    public static List<String> downwindOf(BenchLocation source, Map<String, BenchLocation> candidates, int reach) {
        List<BenchLocation> warmed = source.shelvesAbove(reach);
        Map<BenchLocation, Integer> order = new HashMap<>();
        for (int index = 0; index < warmed.size(); index++) {
            order.put(warmed.get(index), index);
        }
        return candidates.entrySet().stream()
                .filter(entry -> order.containsKey(entry.getValue()))
                .sorted(Comparator.<Map.Entry<String, BenchLocation>>comparingInt(entry -> order.get(entry.getValue()))
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();
    }
}
