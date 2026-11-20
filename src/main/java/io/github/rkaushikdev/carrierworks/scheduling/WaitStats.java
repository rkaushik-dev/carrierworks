package io.github.rkaushikdev.carrierworks.scheduling;

import java.time.Duration;

/**
 * How long the queue has been for the requests still on it.
 *
 * @param waiting how many requests are waiting
 * @param longest the longest any of them has waited
 * @param median the middle wait, or the midpoint of the middle two
 */
public record WaitStats(int waiting, Duration longest, Duration median) {

    /** Nothing waiting at all. */
    public static WaitStats none() {
        return new WaitStats(0, Duration.ZERO, Duration.ZERO);
    }

    /** Whether nothing is waiting. */
    public boolean isEmpty() {
        return waiting == 0;
    }

    /** {@code 3 waiting, longest 40m, median 30m} in whole minutes, or {@code nothing waiting}. */
    public String describe() {
        if (isEmpty()) {
            return "nothing waiting";
        }
        return waiting + " waiting, longest " + wholeMinutes(longest) + "m, median " + wholeMinutes(median) + "m";
    }

    private static long wholeMinutes(Duration duration) {
        return Math.floorDiv(duration.getSeconds(), 60);
    }
}
