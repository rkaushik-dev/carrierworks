package io.github.rkaushikdev.carrierworks.cli;

import java.time.Duration;

/** How long something has been going on, the way a listing column prints it. */
final class Durations {

    private static final long DAY = 86_400;

    private Durations() {}

    /**
     * {@code 1:02:03}, or {@code 2 days, 1:02:03} past a day, to the whole second rounded down. A
     * negative length counts back whole days and forward again, so a second short of nothing is
     * {@code -1 day, 23:59:59}.
     */
    static String clock(Duration length) {
        long seconds = length.getSeconds();
        long days = Math.floorDiv(seconds, DAY);
        long rest = Math.floorMod(seconds, DAY);
        String time = rest / 3600 + ":" + two(rest / 60 % 60) + ":" + two(rest % 60);
        if (days == 0) {
            return time;
        }
        return days + (Math.abs(days) == 1 ? " day, " : " days, ") + time;
    }

    private static String two(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }
}
