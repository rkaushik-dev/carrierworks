package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * One attempt to put one image on one board: what was asked for and what became of it.
 *
 * <p>A job carries its attempts rather than being retried by something outside it. The count is on
 * the record because the third attempt failing is a different event from the first one failing, and
 * only the record knows which this is.
 *
 * <p>A job is immutable. Every move along its life ({@link #start}, {@link #passed}, {@link
 * #failed}, {@link #cancel}) hands back a new job and refuses a move its state does not allow.
 *
 * @param id the job id, as a slug
 * @param board the board's asset tag
 * @param digest the full SHA-256 of the image, as 64 hex digits
 * @param mode how the image goes onto the board
 * @param requestedBy who asked for it, as a slug
 * @param createdAt when it was asked for
 * @param state where the job has got to
 * @param attempts how many times it has been started
 * @param verify whether the image is read back after it is written
 * @param startedAt when it was first started, if it has been
 * @param finishedAt when it ended, if it has
 * @param error why the last attempt failed or the job was cancelled, or empty
 * @param errorCode the stable code of the last failure, or empty
 * @param clocks how many JTAG clocks the passing attempt took
 * @param lease the lease the job ran under, or empty
 */
public record ProgramJob(
        String id,
        String board,
        String digest,
        JobMode mode,
        String requestedBy,
        Instant createdAt,
        JobState state,
        int attempts,
        boolean verify,
        Optional<Instant> startedAt,
        Optional<Instant> finishedAt,
        String error,
        String errorCode,
        long clocks,
        String lease) {

    private static final int DIGEST_LENGTH = 64;
    private static final int SHORT_DIGEST_LENGTH = 12;

    public ProgramJob {
        id = Ids.slug(id);
        board = Records.boardKey(board);
        requestedBy = Ids.slug(requestedBy);
        if (digest.length() != DIGEST_LENGTH) {
            throw new ValidationError("a job carries a full digest", "digest");
        }
        if (attempts < 0) {
            throw new ValidationError("attempts are counted up", "attempts");
        }
    }

    /** A new queued job, with the rest set on the builder. */
    public static Builder builder(
            String id, String board, String digest, JobMode mode, String requestedBy, Instant createdAt) {
        return new Builder(id, board, digest, mode, requestedBy, createdAt);
    }

    /** A builder holding everything this job holds, to change one or two things. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Whether the job is over: passed, failed or cancelled. */
    public boolean finished() {
        return state.finished();
    }

    /** Whether the job passed. */
    public boolean succeeded() {
        return state == JobState.PASSED;
    }

    /** Whether the job changes what is in the configuration flash. */
    public boolean writesFlash() {
        return mode.writesFlash();
    }

    /** The one mode that can leave a board unable to boot at all. */
    public boolean touchesGolden() {
        return mode.touchesGolden();
    }

    /** The first twelve hex digits of the digest, which is how a person reads one. */
    public String shortDigest() {
        return digest.substring(0, SHORT_DIGEST_LENGTH);
    }

    /** From the first start to the end, once the job has both. */
    public Optional<Duration> duration() {
        if (startedAt.isEmpty() || finishedAt.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(startedAt.get(), finishedAt.get()));
    }

    /** {@link #start(Instant, String)} under the lease the job already names. */
    public ProgramJob start(Instant now) {
        return start(now, "");
    }

    /**
     * The job running, with the attempt counted.
     *
     * <p>The start time is the first attempt's, so a retried job's duration covers every attempt. An
     * empty lease keeps the one the job already names.
     */
    public ProgramJob start(Instant now, String lease) {
        if (finished()) {
            throw new ValidationError("that job is over", "job", Details.of("id", id));
        }
        if (state == JobState.RUNNING) {
            throw new ValidationError("that job is already running", "job", Details.of("id", id));
        }
        return toBuilder()
                .state(JobState.RUNNING)
                .attempts(attempts + 1)
                .startedAt(startedAt.orElse(now))
                .lease(lease.isEmpty() ? this.lease : lease)
                .build();
    }

    /** {@link #passed(Instant, long)} with no clock count. */
    public ProgramJob passed(Instant now) {
        return passed(now, 0);
    }

    /** The job passed, which clears the error; only a running job can pass. */
    public ProgramJob passed(Instant now, long clocks) {
        if (state != JobState.RUNNING) {
            throw new ValidationError("a job passes from running", "job", Details.of("id", id));
        }
        return toBuilder()
                .state(JobState.PASSED)
                .finishedAt(now)
                .clocks(clocks)
                .error("")
                .build();
    }

    /** A permanent failure with no code. */
    public ProgramJob failed(Instant now, String reason) {
        return failed(now, reason, "", false);
    }

    /** A permanent failure with a code. */
    public ProgramJob failed(Instant now, String reason, String code) {
        return failed(now, reason, code, false);
    }

    /**
     * Record a failure, and go back to queued when it is worth retrying.
     *
     * <p>Only a running job can fail, and a failure has to say why. A retryable one goes back on the
     * queue with no end time; any other ends the job.
     */
    public ProgramJob failed(Instant now, String reason, String code, boolean retryable) {
        if (state != JobState.RUNNING) {
            throw new ValidationError("a job fails from running", "job", Details.of("id", id));
        }
        if (reason.isBlank()) {
            throw new ValidationError("a failure has a reason", "reason");
        }
        return toBuilder()
                .state(retryable ? JobState.QUEUED : JobState.FAILED)
                .finishedAt(retryable ? Optional.empty() : Optional.of(now))
                .error(reason)
                .errorCode(code)
                .build();
    }

    /** {@link #cancel(Instant, String)} giving no particular reason. */
    public ProgramJob cancel(Instant now) {
        return cancel(now, "cancelled");
    }

    /** The job cancelled, with the reason kept as its error; a finished job cannot be. */
    public ProgramJob cancel(Instant now, String reason) {
        if (finished()) {
            throw new ValidationError("that job is over", "job", Details.of("id", id));
        }
        return toBuilder()
                .state(JobState.CANCELLED)
                .finishedAt(now)
                .error(reason)
                .build();
    }

    /** Whether the job has been started as many times as it is allowed. */
    public boolean exhausted(int limit) {
        return attempts >= limit;
    }

    /** {@code job-00001 jtag abababababab on bx-14 [failed] image is for another device}. */
    public String describe() {
        String line = id + " " + mode.value() + " " + shortDigest() + " on " + board + " [" + state.value() + "]";
        return error.isEmpty() ? line : line + " " + error;
    }

    /** A job assembled a field at a time and checked once, on {@link #build()}. */
    public static final class Builder {

        private final String id;
        private final String board;
        private final String digest;
        private final JobMode mode;
        private final String requestedBy;
        private final Instant createdAt;
        private JobState state = JobState.QUEUED;
        private int attempts;
        private boolean verify = true;
        private Optional<Instant> startedAt = Optional.empty();
        private Optional<Instant> finishedAt = Optional.empty();
        private String error = "";
        private String errorCode = "";
        private long clocks;
        private String lease = "";

        private Builder(String id, String board, String digest, JobMode mode, String requestedBy, Instant createdAt) {
            this.id = id;
            this.board = board;
            this.digest = digest;
            this.mode = mode;
            this.requestedBy = requestedBy;
            this.createdAt = createdAt;
        }

        private Builder(ProgramJob from) {
            this(from.id, from.board, from.digest, from.mode, from.requestedBy, from.createdAt);
            state = from.state;
            attempts = from.attempts;
            verify = from.verify;
            startedAt = from.startedAt;
            finishedAt = from.finishedAt;
            error = from.error;
            errorCode = from.errorCode;
            clocks = from.clocks;
            lease = from.lease;
        }

        public Builder state(JobState value) {
            state = value;
            return this;
        }

        public Builder attempts(int value) {
            attempts = value;
            return this;
        }

        public Builder verify(boolean value) {
            verify = value;
            return this;
        }

        public Builder startedAt(Instant value) {
            return startedAt(Optional.of(value));
        }

        /** When the job was first started, or empty for a job that never has been. */
        public Builder startedAt(Optional<Instant> value) {
            startedAt = value;
            return this;
        }

        public Builder finishedAt(Instant value) {
            return finishedAt(Optional.of(value));
        }

        /** When the job ended, or empty for a job that has not. */
        public Builder finishedAt(Optional<Instant> value) {
            finishedAt = value;
            return this;
        }

        public Builder error(String value) {
            error = value;
            return this;
        }

        public Builder errorCode(String value) {
            errorCode = value;
            return this;
        }

        public Builder clocks(long value) {
            clocks = value;
            return this;
        }

        public Builder lease(String value) {
            lease = value;
            return this;
        }

        /** The job, checked. */
        public ProgramJob build() {
            return new ProgramJob(
                    id,
                    board,
                    digest,
                    mode,
                    requestedBy,
                    createdAt,
                    state,
                    attempts,
                    verify,
                    startedAt,
                    finishedAt,
                    error,
                    errorCode,
                    clocks,
                    lease);
        }
    }
}
