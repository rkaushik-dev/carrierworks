package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import java.time.Instant;
import java.util.Optional;

/**
 * A job on the way out.
 *
 * @param id the job id, such as {@code job-00001}
 * @param board the board it programs
 * @param digest the image it programs it with
 * @param mode how
 * @param requestedBy who asked for it
 * @param state where it stands: queued, running, passed, failed or cancelled
 * @param attempts how many times it has been started
 * @param verify whether the board is read back afterwards
 * @param createdAt when it was asked for
 * @param startedAt when it last started, or null if it never has
 * @param finishedAt when it finished, or null while it has not
 * @param error what went wrong, or nothing
 * @param errorCode the bench's code for what went wrong, or nothing
 */
public record JobOut(
        String id,
        String board,
        String digest,
        String mode,
        String requestedBy,
        String state,
        int attempts,
        boolean verify,
        Instant createdAt,
        Optional<Instant> startedAt,
        Optional<Instant> finishedAt,
        String error,
        String errorCode) {

    /** One job, as it stands. */
    public static JobOut of(ProgramJob job) {
        return new JobOut(
                job.id(),
                job.board(),
                job.digest(),
                job.mode().value(),
                job.requestedBy(),
                job.state().value(),
                job.attempts(),
                job.verify(),
                job.createdAt(),
                job.startedAt(),
                job.finishedAt(),
                job.error(),
                job.errorCode());
    }
}
