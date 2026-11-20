package io.github.rkaushikdev.carrierworks;

import java.util.Map;

/**
 * The scan chain did not read back as a chain.
 *
 * <p>Retryable, and the reason is the ribbon cable. A chain that reads as all ones once and
 * correctly the second time is the normal way a loose header presents, so the job runner gets to
 * try again before anyone is called.
 */
public class ChainError extends TransientBenchError {

    public ChainError(String message) {
        super(message);
    }

    public ChainError(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "chain";
    }
}
