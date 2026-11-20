package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.TransientBenchError;
import java.util.Map;

/**
 * The device did not come up configured after a load.
 *
 * <p>Retryable, because the usual cause is the cable rather than the image, and the gates upstream
 * have already ruled out most of the reasons an image would be wrong for a board.
 */
public class ProgrammingFailed extends TransientBenchError {

    public ProgrammingFailed(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "programming";
    }
}
