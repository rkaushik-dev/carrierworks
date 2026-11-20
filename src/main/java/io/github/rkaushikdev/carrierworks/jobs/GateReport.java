package io.github.rkaushikdev.carrierworks.jobs;

import java.util.List;
import java.util.Objects;

/**
 * What stands between a job and the board it wants.
 *
 * @param job the job's id
 * @param blockers every reason the job may not run, in the order the gates found them; none when it
 *     may
 */
public record GateReport(String job, List<String> blockers) {

    public GateReport {
        Objects.requireNonNull(job, "job");
        blockers = List.copyOf(blockers);
    }

    /** A job nothing stands in the way of. */
    public GateReport(String job) {
        this(job, List.of());
    }

    /** Whether the job may run. */
    public boolean allowed() {
        return blockers.isEmpty();
    }

    /** {@code job-00001 may run}, or {@code job-00001 refused: } and every blocker. */
    public String describe() {
        if (allowed()) {
            return job + " may run";
        }
        return job + " refused: " + String.join("; ", blockers);
    }
}
