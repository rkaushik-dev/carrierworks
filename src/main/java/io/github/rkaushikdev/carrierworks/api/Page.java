package io.github.rkaushikdev.carrierworks.api;

import java.util.List;

/**
 * How much of a listing to return, and from where.
 *
 * <p>One page for every listing route, rather than a limit repeated in each with its own maximum.
 * Nothing on this bench has more rows than {@link #MAX} that anybody wants in one go. A route asks
 * for one by taking a {@code Page} parameter; it is read from the {@code limit} and {@code offset}
 * query parameters.
 *
 * @param limit how many rows at most, one to {@link #MAX}
 * @param offset how many rows to skip first
 */
public record Page(int limit, int offset) {

    /** The most rows one page holds. */
    public static final int MAX = 500;

    /** How many rows a page holds unless it is asked for more or fewer. */
    public static final int DEFAULT_LIMIT = 50;

    /**
     * The window of a listing this page asks for.
     *
     * <p>Applied after the query rather than inside it. Every listing on this bench is at most a
     * few thousand rows, and paging in the database would mean every repository method growing two
     * arguments it does not otherwise need.
     */
    public <T> List<T> slice(List<T> items) {
        int from = Math.min(offset, items.size());
        int to = (int) Math.min((long) from + limit, items.size());
        return List.copyOf(items.subList(from, to));
    }
}
