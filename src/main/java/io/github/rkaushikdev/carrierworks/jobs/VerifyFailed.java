package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.BenchError;
import java.util.Map;

/**
 * The image read back from the flash is not the image that was written.
 *
 * <p>Deliberately not retryable. A verify failure means the part did not hold what it was given,
 * and doing it again writes over the evidence.
 */
public class VerifyFailed extends BenchError {

    public VerifyFailed(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "verify";
    }
}
