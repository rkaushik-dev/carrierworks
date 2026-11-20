package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * When a pattern of faults is enough to stop believing a board.
 *
 * <p>The policy is deliberately conservative on the kinds of fault that come and go, and immediate
 * on the kinds that do not: a verify failure means the flash wrote something other than what was
 * sent, and two of those on one board is a part that is wearing out rather than a bad day.
 *
 * <p>The window matters as much as the count. Three faults in a fortnight is a board being used;
 * three faults in an afternoon is a board that has broken.
 *
 * @param window the long window the total and the verify failures are counted over
 * @param totalFaults faults of any kind inside the long window that are enough
 * @param shortWindow the short window, inside the long one
 * @param shortWindowFaults faults of any kind inside the short window that are enough
 * @param verifyFaults verify failures inside the long window that are enough
 */
public record QuarantinePolicy(
        Duration window, int totalFaults, Duration shortWindow, int shortWindowFaults, int verifyFaults) {

    /** The long window unless somebody says otherwise: a week. */
    public static final Duration DEFAULT_WINDOW = Duration.ofDays(7);

    /** The short window unless somebody says otherwise: a working day. */
    public static final Duration DEFAULT_SHORT_WINDOW = Duration.ofHours(8);

    public QuarantinePolicy {
        if (totalFaults < 1 || shortWindowFaults < 1 || verifyFaults < 1) {
            throw new ValidationError("a threshold is at least one fault", "policy");
        }
        if (shortWindow.compareTo(window) > 0) {
            throw new ValidationError("the short window is inside the long one", "short_window");
        }
    }

    /** Three faults in a week, two in eight hours, or two verify failures in a week. */
    public QuarantinePolicy() {
        this(DEFAULT_WINDOW, 3, DEFAULT_SHORT_WINDOW, 2, 2);
    }

    /** The same thresholds counted over other windows. */
    public QuarantinePolicy withWindows(Duration longWindow, Duration shortOne) {
        return new QuarantinePolicy(longWindow, totalFaults, shortOne, shortWindowFaults, verifyFaults);
    }

    /** The same windows with other thresholds, for a bench with older boards. */
    public QuarantinePolicy withThresholds(int total, int inShortWindow, int verify) {
        return new QuarantinePolicy(window, total, shortWindow, inShortWindow, verify);
    }

    /**
     * Why this board should be quarantined, or nothing if it should not.
     *
     * <p>Checked most specific first, so the reason an operator reads is the sharpest one that
     * applies rather than whichever happened to be checked first.
     */
    public Optional<String> verdict(FaultLog log, String board, Instant now) {
        int verifies = log.count(board, now, window, FaultKind.VERIFY);
        if (verifies >= verifyFaults) {
            return Optional.of(verifies + " verify failures inside " + days(window));
        }
        int recent = log.count(board, now, shortWindow);
        if (recent >= shortWindowFaults) {
            return Optional.of(recent + " faults inside " + hours(shortWindow));
        }
        int total = log.count(board, now, window);
        if (total >= totalFaults) {
            return Optional.of(total + " faults inside " + days(window));
        }
        return Optional.empty();
    }

    /** Whether the faults on this board are enough to pull it. */
    public boolean shouldQuarantine(FaultLog log, String board, Instant now) {
        return verdict(log, board, now).isPresent();
    }

    private static String days(Duration span) {
        long days = span.toDays();
        return days == 1 ? "a day" : days + " days";
    }

    private static String hours(Duration span) {
        long hours = span.toHours();
        return hours == 1 ? "an hour" : hours + " hours";
    }
}
