package io.github.rkaushikdev.carrierworks.services;

import java.util.Locale;
import java.util.stream.Stream;

/**
 * The next number in a run of ids such as {@code lease-00041}, carried on from the ones stored.
 *
 * <p>Numbers are derived from what is already in the database rather than from a counter in memory.
 * A restart in the middle of a busy afternoon would otherwise start again at one and collide with
 * everything issued that morning.
 */
final class Numbering {

    /** More digits than this cannot be a number the bench issued, and would not fit a long. */
    private static final int MAX_DIGITS = 18;

    private Numbering() {}

    /** {@code prefix-NNNNN}, one past the highest number any of the ids ends in. */
    static String next(String prefix, int width, Stream<String> ids) {
        long highest = ids.mapToLong(Numbering::numberOf).max().orElse(0);
        return String.format(Locale.ROOT, "%s-%0" + width + "d", prefix, highest + 1);
    }

    /** The number after the last hyphen of an id, or zero when what follows it is not all digits. */
    static long numberOf(String id) {
        String tail = id.substring(id.lastIndexOf('-') + 1);
        if (tail.isEmpty() || tail.length() > MAX_DIGITS || !tail.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            return 0;
        }
        return Long.parseLong(tail);
    }
}
