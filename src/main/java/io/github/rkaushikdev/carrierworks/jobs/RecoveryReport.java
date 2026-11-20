package io.github.rkaushikdev.carrierworks.jobs;

import java.util.ArrayList;
import java.util.List;

/**
 * What the bench found and did when it came back.
 *
 * @param jobsClosed interrupted jobs that were failed, in the order the journal named them
 * @param jobsRequeued interrupted jobs put back on the queue, in the same order
 * @param boardsFaulted boards faulted because a flash write on them was interrupted, sorted
 * @param boardsReturned boards put back in service, sorted
 * @param unknownJobs jobs the journal names that the job records do not have
 */
public record RecoveryReport(
        List<String> jobsClosed,
        List<String> jobsRequeued,
        List<String> boardsFaulted,
        List<String> boardsReturned,
        List<String> unknownJobs) {

    public RecoveryReport {
        jobsClosed = List.copyOf(jobsClosed);
        jobsRequeued = List.copyOf(jobsRequeued);
        boardsFaulted = List.copyOf(boardsFaulted);
        boardsReturned = List.copyOf(boardsReturned);
        unknownJobs = List.copyOf(unknownJobs);
    }

    /**
     * Whether nothing was left half done.
     *
     * <p>Unknown jobs do not count: a journal line for a job nobody has is worth reporting, but it
     * left no board and no job record to put right.
     */
    public boolean clean() {
        return jobsClosed.isEmpty() && jobsRequeued.isEmpty() && boardsFaulted.isEmpty() && boardsReturned.isEmpty();
    }

    /** {@code closed job-00001; faulted bx-15}, or {@code nothing was left half done}. */
    public String describe() {
        if (clean()) {
            return "nothing was left half done";
        }
        List<String> parts = new ArrayList<>();
        if (!jobsClosed.isEmpty()) {
            parts.add("closed " + String.join(", ", jobsClosed));
        }
        if (!jobsRequeued.isEmpty()) {
            parts.add("requeued " + String.join(", ", jobsRequeued));
        }
        if (!boardsFaulted.isEmpty()) {
            parts.add("faulted " + String.join(", ", boardsFaulted));
        }
        if (!boardsReturned.isEmpty()) {
            parts.add("returned " + String.join(", ", boardsReturned));
        }
        if (!unknownJobs.isEmpty()) {
            parts.add("journal mentions unknown jobs " + String.join(", ", unknownJobs));
        }
        return String.join("; ", parts);
    }
}
