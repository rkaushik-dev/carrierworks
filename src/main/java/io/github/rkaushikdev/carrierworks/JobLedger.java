package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;

/**
 * Reading one job back and writing what became of it.
 *
 * <p>Two methods because that is what recovery needs. Anything that wants to ask the queue a
 * question uses the repository or the store directly.
 */
public interface JobLedger {

    /** One job, or a not found. */
    ProgramJob job(String jobId);

    /** Write a job's new state, handing the stored job back. */
    ProgramJob update(ProgramJob job);
}
