package io.github.rkaushikdev.carrierworks;

import java.util.Locale;

/**
 * Short ids for leases, jobs and runs.
 *
 * <p>Sequential rather than random, and the prefix says what the thing is, so a job id read out
 * over the bench is unambiguous. The counter is handed in so a restart can carry on from the
 * database rather than replaying ids that already exist.
 */
public final class IdFactory {

    private final String prefix;
    private final int width;
    private final long limit;
    private long counter;

    /** Ids {@code prefix-00001} onwards. */
    public IdFactory(String prefix) {
        this(prefix, 1, 5);
    }

    /** Ids starting at {@code start}, zero padded to five digits. */
    public IdFactory(String prefix, long start) {
        this(prefix, start, 5);
    }

    /** Ids starting at {@code start}, zero padded to {@code width} digits. */
    public IdFactory(String prefix, long start, int width) {
        this.prefix = Ids.slug(prefix);
        if (start < 1) {
            throw new ValidationError("id counter starts at one", "start", Details.of("given", start));
        }
        if (width < 3 || width > 9) {
            throw new ValidationError("id width is unreasonable", "width", Details.of("given", width));
        }
        this.counter = start - 1;
        this.width = width;
        this.limit = (long) Math.pow(10, width);
    }

    /** How many ids have been handed out, counting the ones before the start. */
    public long issued() {
        return counter;
    }

    /** Spend the next id. */
    public String next() {
        counter += 1;
        if (counter >= limit) {
            throw new ValidationError("id space exhausted", "counter", Details.of("prefix", prefix, "width", width));
        }
        return render(counter);
    }

    /** What the next id would be, without spending it. */
    public String peek() {
        return render(counter + 1);
    }

    private String render(long value) {
        return String.format(Locale.ROOT, "%s-%0" + width + "d", prefix, value);
    }
}
