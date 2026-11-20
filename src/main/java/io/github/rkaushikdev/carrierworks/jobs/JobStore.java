package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.IdFactory;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every job the bench has been asked for, where jobs wait, and the rule about not running two on
 * one board.
 *
 * <p>The store is a queue with one extra thing in it: a board can only be in one job at a time.
 * That sounds obvious and it is the invariant that a naive worker pool breaks first, because two
 * jobs for one board look independent right up until they are both halfway through a flash erase.
 *
 * <p>Retries are held here as well. A job that failed transiently is queued again with a time before
 * which it will not be offered, so the runner does not have to sleep and the backoff survives a
 * restart of whatever is calling it.
 */
public final class JobStore {

    private static final Comparator<ProgramJob> OLDEST_FIRST =
            Comparator.comparing(ProgramJob::createdAt).thenComparing(ProgramJob::id);

    private final Map<String, ProgramJob> jobs = new TreeMap<>();
    private final Map<String, Instant> notBefore = new HashMap<>();
    private final IdFactory ids;

    /** A store numbering its jobs from {@code job-00001}. */
    public JobStore() {
        this(1);
    }

    /** A store numbering its jobs from {@code start}, to carry on after the ones that exist. */
    public JobStore(long start) {
        this.ids = new IdFactory("job", start);
    }

    /** Queue a job that reads the image back after writing it. */
    public ProgramJob submit(String board, String digest, JobMode mode, String requestedBy, Instant now) {
        return submit(board, digest, mode, requestedBy, now, true);
    }

    /** Queue a job under the next id. */
    public ProgramJob submit(
            String board, String digest, JobMode mode, String requestedBy, Instant now, boolean verify) {
        ProgramJob job = ProgramJob.builder(ids.next(), board, digest, mode, requestedBy, now)
                .verify(verify)
                .build();
        jobs.put(job.id(), job);
        return job;
    }

    /** One job by id, or a not found. */
    public ProgramJob job(String jobId) {
        String id = Ids.slug(jobId);
        ProgramJob found = jobs.get(id);
        if (found == null) {
            throw new NotFound("job", id);
        }
        return found;
    }

    /** Write a job back after the runner has moved it along; a finished job forgets its backoff. */
    public ProgramJob update(ProgramJob job) {
        if (!jobs.containsKey(job.id())) {
            throw new NotFound("job", job.id());
        }
        jobs.put(job.id(), job);
        if (job.finished()) {
            notBefore.remove(job.id());
        }
        return job;
    }

    /** Keep a job off the ready list until a time, which is the backoff. */
    public void holdUntil(String jobId, Instant when) {
        notBefore.put(job(jobId).id(), when);
    }

    /** When a job's backoff ends, if it has one. */
    public Optional<Instant> heldUntil(String jobId) {
        return Optional.ofNullable(notBefore.get(Ids.slug(jobId)));
    }

    /** Cancel a job with no particular reason. */
    public ProgramJob cancel(String jobId, Instant now) {
        return cancel(jobId, now, "cancelled");
    }

    /** Cancel a job and keep the reason as its error. */
    public ProgramJob cancel(String jobId, Instant now, String reason) {
        return update(job(jobId).cancel(now, reason));
    }

    /** Every job, in id order. */
    public List<ProgramJob> all() {
        return List.copyOf(jobs.values());
    }

    /** The jobs in one state, in id order. */
    public List<ProgramJob> inState(JobState state) {
        return all().stream().filter(job -> job.state() == state).toList();
    }

    /** The jobs waiting to run, in id order. */
    public List<ProgramJob> queued() {
        return inState(JobState.QUEUED);
    }

    /** The jobs running now, in id order. */
    public List<ProgramJob> running() {
        return inState(JobState.RUNNING);
    }

    /** Every job for one board, in id order. */
    public List<ProgramJob> forBoard(String board) {
        String tag = Records.boardKey(board);
        return all().stream().filter(job -> job.board().equals(tag)).toList();
    }

    /** The boards with a job running on them, sorted. */
    public List<String> busyBoards() {
        return running().stream().map(ProgramJob::board).distinct().sorted().toList();
    }

    /**
     * Jobs that could start right now, oldest first.
     *
     * <p>A job is ready when it is queued, its backoff has passed, and nothing else is running on its
     * board. Two queued jobs for one board means the older one is offered and the other waits, which
     * is the whole point.
     */
    public List<ProgramJob> ready(Instant now) {
        Set<String> busy = new HashSet<>(busyBoards());
        List<ProgramJob> offered = new ArrayList<>();
        for (ProgramJob job : queued().stream().sorted(OLDEST_FIRST).toList()) {
            if (backingOff(job, now) || busy.contains(job.board())) {
                continue;
            }
            busy.add(job.board());
            offered.add(job);
        }
        return List.copyOf(offered);
    }

    /** The job that would be offered first, if any is ready. */
    public Optional<ProgramJob> nextReady(Instant now) {
        return ready(now).stream().findFirst();
    }

    /** Queued jobs still inside their backoff, in id order. */
    public List<ProgramJob> waitingForBackoff(Instant now) {
        return queued().stream().filter(job -> backingOff(job, now)).toList();
    }

    /** The board's job with the highest id, if it has any. */
    public Optional<ProgramJob> lastForBoard(String board) {
        List<ProgramJob> found = forBoard(board);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getLast());
    }

    /**
     * Fail any job that has been running longer than it possibly should.
     *
     * <p>This is the safety net for a worker that died without writing anything: the job sits in
     * running forever and its board is never offered again. Anything past the limit is closed with
     * the code {@code abandoned} and named in the answer.
     */
    public List<ProgramJob> abandonStale(Instant now, Duration olderThan) {
        if (!olderThan.isPositive()) {
            throw new ValidationError("a limit has some length", "older_than");
        }
        List<ProgramJob> closed = new ArrayList<>();
        for (ProgramJob job : running()) {
            Optional<Instant> started = job.startedAt();
            if (started.isPresent() && Duration.between(started.get(), now).compareTo(olderThan) > 0) {
                ProgramJob dead = job.failed(now, "the worker running this job went away", "abandoned");
                closed.add(update(dead));
            }
        }
        return List.copyOf(closed);
    }

    /** How many jobs the store holds. */
    public int size() {
        return jobs.size();
    }

    private boolean backingOff(ProgramJob job, Instant now) {
        Instant hold = notBefore.get(job.id());
        return hold != null && now.isBefore(hold);
    }
}
