package io.github.rkaushikdev.carrierworks;

import java.util.Map;

/**
 * Something out in the lab was unhappy and may not be next time.
 *
 * <p>Cables, adapters, power distribution units and the flash chips themselves all fail
 * intermittently. Subclasses of this get another go.
 */
public class TransientBenchError extends BenchError {

    public TransientBenchError(String message) {
        super(message);
    }

    public TransientBenchError(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "transient";
    }

    @Override
    public boolean retryable() {
        return true;
    }
}
