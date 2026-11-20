package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.BoardRegistry;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.JobLedger;
import io.github.rkaushikdev.carrierworks.LeaseRegistry;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Picking the bench up after something died in the middle of a job.
 *
 * <p>Two sources are read and they disagree in an informative way. The journal knows which jobs
 * were started and never closed, because it is written before each step. The board registry knows
 * which boards were left in the programming state, because that is written when a job starts. A
 * board in the second list without a job in the first is a board somebody moved by hand; a job in
 * the first without a board in the second is a job whose board has since been reset.
 *
 * <p>What happens to a board depends on what was being done to it. A JTAG load that was interrupted
 * leaves a device in an unknown but harmless state: the next load clears it, so the board goes back
 * to service. A flash write that was interrupted leaves a part that may be half erased, and that
 * board is faulted until somebody has looked at it, because the next boot from it is the one that
 * matters.
 *
 * <p>Recovery is idempotent. Running it twice finds nothing the second time, which matters because
 * it runs at startup and startups happen in threes when something is really wrong.
 */
public final class Recovery {

    private static final String ACTOR = "recovery";

    private Recovery() {}

    /** {@link #recover(JobJournal, JobLedger, BoardRegistry, LeaseRegistry, Instant, int)} ending every interrupted job. */
    public static RecoveryReport recover(
            JobJournal journal, JobLedger jobs, BoardRegistry book, LeaseRegistry leases, Instant now) {
        return recover(journal, jobs, book, leases, now, 0);
    }

    /**
     * Close what was left open and put the boards somewhere honest.
     *
     * <p>A retry limit puts an interrupted load back on the queue rather than failing it, as long as
     * it has attempts left and it was not writing flash. A flash write is never retried on its own,
     * because the part it was writing is in a state nothing can read back and be sure of. Zero means
     * every interrupted job ends.
     *
     * @param retryLimit how many attempts a job may have had and still be requeued
     */
    public static RecoveryReport recover(
            JobJournal journal, JobLedger jobs, BoardRegistry book, LeaseRegistry leases, Instant now, int retryLimit) {
        List<String> closed = new ArrayList<>();
        List<String> requeued = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        Set<String> boardsSeen = new HashSet<>();
        Settler settler = new Settler(book, leases, now);

        for (String jobId : journal.unfinished()) {
            Optional<ProgramJob> found = lookUp(jobs, jobId);
            if (found.isEmpty()) {
                unknown.add(jobId);
                journal.append(jobId, JournalEvent.ABANDONED, now, Details.of("reason", "job record is gone"));
                continue;
            }
            ProgramJob job = found.get();
            boardsSeen.add(job.board());
            if (!job.finished()) {
                ProgramJob running = job.state() == JobState.RUNNING ? job : job.start(now);
                boolean retryable = retryLimit > 0 && !job.writesFlash() && !job.exhausted(retryLimit);
                jobs.update(running.failed(now, "the bench restarted during this job", "abandoned", retryable));
                (retryable ? requeued : closed).add(job.id());
            }
            journal.append(job.id(), JournalEvent.ABANDONED, now, Details.of("board", job.board()));
            settler.settle(job.board(), job.writesFlash());
        }

        for (Board board : book.inState(BoardStatus.PROGRAMMING)) {
            if (!boardsSeen.contains(board.tag())) {
                settler.settle(board.tag(), false);
            }
        }

        return new RecoveryReport(closed, requeued, settler.faulted(), settler.returned(), unknown);
    }

    /**
     * The job record behind a journal line, or empty when it cannot be had.
     *
     * <p>A journal can outlive a job store, and a record that cannot be read back is as gone as one
     * that was deleted, so any failure to read it counts as the record being gone.
     */
    private static Optional<ProgramJob> lookUp(JobLedger jobs, String jobId) {
        try {
            return Optional.of(jobs.job(jobId));
        } catch (RuntimeException gone) {
            return Optional.empty();
        }
    }

    /** Moves boards out of the state a dead job left them in, and remembers where each one went. */
    private static final class Settler {

        private final BoardRegistry book;
        private final LeaseRegistry leases;
        private final Instant now;
        private final Set<String> faulted = new TreeSet<>();
        private final Set<String> returned = new TreeSet<>();

        Settler(BoardRegistry book, LeaseRegistry leases, Instant now) {
            this.book = book;
            this.leases = leases;
            this.now = now;
        }

        /** The boards faulted so far, sorted. */
        List<String> faulted() {
            return List.copyOf(faulted);
        }

        /** The boards put back in service so far, sorted. */
        List<String> returned() {
            return List.copyOf(returned);
        }

        /** Move one board on, if it is still known and still programming. */
        void settle(String board, boolean writesFlash) {
            if (!book.has(board) || book.state(board).state() != BoardStatus.PROGRAMMING) {
                return;
            }
            if (writesFlash) {
                book.moveTo(board, BoardStatus.LEASED, now, "", ACTOR);
                book.moveTo(board, BoardStatus.FAULTED, now, "a flash write was interrupted by a restart", ACTOR);
                Optional<Lease> held = leases.forBoard(board, now);
                held.ifPresent(lease -> leases.release(lease.id(), now, "board faulted during recovery"));
                faulted.add(board);
                return;
            }
            book.moveTo(board, BoardStatus.LEASED, now, "", ACTOR);
            if (leases.forBoard(board, now).isEmpty()) {
                book.moveTo(board, BoardStatus.AVAILABLE, now, "", ACTOR);
            }
            returned.add(board);
        }
    }
}
