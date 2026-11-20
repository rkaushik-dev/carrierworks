package io.github.rkaushikdev.carrierworks.jobs;

import java.util.Objects;

/**
 * The job after an attempt, and what the bench did about it.
 *
 * @param job the job as the attempt left it
 * @param detail what the attempt came to: the outcome when it passed, the reason when it did not
 * @param faultedBoard whether the board was faulted because the failure left it in an unknown state
 * @param retryInSeconds how long to wait before the next attempt; none when there will not be one
 */
public record RunResult(ProgramJob job, String detail, boolean faultedBoard, int retryInSeconds) {

    public RunResult {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(detail, "detail");
    }

    /** An attempt that left the board as it was and will not be retried. */
    public RunResult(ProgramJob job, String detail) {
        this(job, detail, false, 0);
    }

    /** Whether the job passed. */
    public boolean passed() {
        return job.succeeded();
    }

    /** Whether the job went back on the queue. */
    public boolean willRetry() {
        return job.state() == JobState.QUEUED;
    }

    /** {@code job-00001 passed: ...}, or for a retry {@code job-00001 queued: ..., retry in 15s}. */
    public String describe() {
        String line = job.id() + " " + job.state().value() + ": " + detail;
        if (willRetry()) {
            return line + ", retry in " + retryInSeconds + "s";
        }
        return line;
    }
}
