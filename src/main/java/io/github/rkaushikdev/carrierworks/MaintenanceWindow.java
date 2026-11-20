package io.github.rkaushikdev.carrierworks;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * A stretch where some or all of the bench is out of use.
 *
 * @param start when it begins, inclusive
 * @param end when it ends, exclusive
 * @param reason what a refused job is told
 * @param scope {@code bench} for every rack, otherwise the one rack it covers
 */
public record MaintenanceWindow(Instant start, Instant end, String reason, String scope) {

    /** The scope of a window that covers every rack. */
    public static final String BENCH = "bench";

    public MaintenanceWindow {
        if (!end.isAfter(start)) {
            throw new ValidationError("a window ends after it starts", "window");
        }
        scope = Ids.slug(scope);
    }

    /** A window over the whole bench. */
    public MaintenanceWindow(Instant start, Instant end, String reason) {
        this(start, end, reason, BENCH);
    }

    /** How long the window is, in whole minutes. */
    public long minutes() {
        return Duration.between(start, end).toMinutes();
    }

    /** Whether the window is in force at this moment. */
    public boolean covers(Instant when) {
        return !when.isBefore(start) && when.isBefore(end);
    }

    /** A bench wide window covers every rack, and no rack at all; a rack window covers one. */
    public boolean appliesTo(Optional<String> rack) {
        if (scope.equals(BENCH)) {
            return true;
        }
        return rack.isPresent() && Ids.slug(rack.get()).equals(scope);
    }

    /** Whether the two windows share any moment. */
    public boolean overlaps(MaintenanceWindow other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }
}
