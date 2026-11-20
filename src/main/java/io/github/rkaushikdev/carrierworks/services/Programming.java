package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.Conflict;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.jobs.JobMode;
import io.github.rkaushikdev.carrierworks.jobs.JobState;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Asking for a board to be programmed, and finding the next job to run.
 *
 * <p>The queue lives in the database rather than in a worker's memory, so a job submitted while
 * nothing is running is still there when a worker starts, and a worker that dies takes nothing with
 * it.
 *
 * <p>One job at a time per board is enforced here rather than in the runner. By the time the runner
 * has a job it is already committed to touching that board, and the check belongs where the
 * decision is made.
 */
public final class Programming {

    /** Why a job taken off the queue was taken off, unless somebody says otherwise. */
    public static final String CANCELLED = "cancelled";

    private static final String PREFIX = "job";
    private static final int WIDTH = 5;

    private Programming() {}

    /** The next job number, carried on from what is already stored: {@code job-00001}. */
    public static String nextJobId(UnitOfWork unit) {
        return Numbering.next(
                PREFIX,
                WIDTH,
                unit.boards().all().stream()
                        .flatMap(board -> unit.jobs().forBoard(board.tag()).stream())
                        .map(ProgramJob::id));
    }

    /** Put a programming job on the queue, verifying the board after it is programmed. */
    public static ProgramJob submit(
            UnitOfWork unit, String tag, String digest, JobMode mode, String requestedBy, Instant now) {
        return submit(unit, tag, digest, mode, requestedBy, now, true);
    }

    /**
     * Put a programming job on the queue.
     *
     * @param verify whether to read the board back after programming it
     * @throws io.github.rkaushikdev.carrierworks.NotFound when the board or the image is not known
     * @throws Conflict when the board already has a job running
     */
    public static ProgramJob submit(
            UnitOfWork unit, String tag, String digest, JobMode mode, String requestedBy, Instant now, boolean verify) {
        Board board = unit.boards().get(tag);
        unit.artifacts().get(digest);
        for (ProgramJob existing : unit.jobs().forBoard(board.tag())) {
            if (existing.state() == JobState.RUNNING) {
                throw new Conflict(
                        "that board is already being programmed",
                        Details.of("board", board.tag(), "job", existing.id()));
            }
        }
        ProgramJob job = ProgramJob.builder(nextJobId(unit), board.tag(), digest, mode, requestedBy, now)
                .verify(verify)
                .build();
        unit.jobs().add(job);
        return job;
    }

    /**
     * Jobs that could start now, oldest first, one per board.
     *
     * <p>A board with a running job is skipped, and so is the second queued job for a board whose
     * first is being offered in this same pass. Without that, two workers pulling at once would both
     * take a job for one board.
     */
    public static List<ProgramJob> ready(UnitOfWork unit, Instant now) {
        Set<String> busy =
                unit.jobs().running().stream().map(ProgramJob::board).collect(Collectors.toCollection(HashSet::new));
        List<ProgramJob> offered = new ArrayList<>();
        for (ProgramJob job : unit.jobs().queued(now)) {
            if (busy.add(job.board())) {
                offered.add(job);
            }
        }
        return List.copyOf(offered);
    }

    /** The oldest job that could start now, if there is one. */
    public static Optional<ProgramJob> nextReady(UnitOfWork unit, Instant now) {
        return ready(unit, now).stream().findFirst();
    }

    /** {@link #cancel(UnitOfWork, String, Instant, String)} giving no particular reason. */
    public static ProgramJob cancel(UnitOfWork unit, String jobId, Instant now) {
        return cancel(unit, jobId, now, CANCELLED);
    }

    /**
     * Take a job off the queue.
     *
     * @throws io.github.rkaushikdev.carrierworks.ValidationError when the job is already over
     */
    public static ProgramJob cancel(UnitOfWork unit, String jobId, Instant now, String reason) {
        return unit.jobs().save(unit.jobs().get(jobId).cancel(now, reason));
    }

    /** Every job a board has had, oldest first. */
    public static List<ProgramJob> forBoard(UnitOfWork unit, String tag) {
        return unit.jobs().forBoard(tag);
    }

    /** Keep a retried job off the ready list until its backoff has passed. */
    public static ProgramJob hold(UnitOfWork unit, ProgramJob job, Instant until) {
        return unit.jobs().save(job, Optional.of(until));
    }
}
