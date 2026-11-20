package io.github.rkaushikdev.carrierworks;

import java.util.Map;

/**
 * Two things wanted the same board, lease or number at the same time.
 *
 * <p>Not a validation failure: what was asked for was perfectly sensible and somebody else got
 * there first. The http layer turns this into a 409 and the scheduler treats it as a reason to
 * look at the next candidate rather than as a reason to stop.
 */
public class Conflict extends BenchError {

    public Conflict(String message) {
        super(message);
    }

    public Conflict(String message, Map<String, ?> details) {
        super(message, details);
    }

    public Conflict(String message, Map<String, ?> details, Throwable cause) {
        super(message, details, cause);
    }

    @Override
    public String code() {
        return "conflict";
    }
}
