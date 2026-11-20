package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Arrays;

/**
 * Where a programming job has got to.
 *
 * <p>A job waits queued, runs, and ends passed, failed or cancelled. A failure worth retrying goes
 * back to queued rather than ending, so the three end states are final.
 */
public enum JobState {
    QUEUED("queued"),
    RUNNING("running"),
    PASSED("passed"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String value;

    JobState(String value) {
        this.value = value;
    }

    /** The name the state is stored, printed and asked for under. */
    public String value() {
        return value;
    }

    /** Whether a job in this state is over: passed, failed or cancelled. */
    public boolean finished() {
        return this == PASSED || this == FAILED || this == CANCELLED;
    }

    /** The state stored under exactly this name. */
    public static JobState parse(String text) {
        return Arrays.stream(values())
                .filter(state -> state.value.equals(text))
                .findFirst()
                .orElseThrow(() -> new ValidationError("no such job state", "state", Details.of("given", text)));
    }

    @Override
    public String toString() {
        return value;
    }
}
