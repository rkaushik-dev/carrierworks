package io.github.rkaushikdev.carrierworks;

import java.util.Map;

/**
 * A rail, an outlet or a distribution unit did not behave.
 *
 * <p>Retryable in the same sense a chain fault is: distribution units drop their control
 * connection regularly and come back on the next attempt. A rail outside its window is not this
 * error, because trying that again is how a board gets damaged.
 */
public class PowerFault extends TransientBenchError {

    public PowerFault(String message) {
        super(message);
    }

    public PowerFault(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "power";
    }
}
