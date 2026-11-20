package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.AuditEntry;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.jobs.JobJournal;
import io.github.rkaushikdev.carrierworks.jobs.JournalEntry;
import io.github.rkaushikdev.carrierworks.jobs.JournalEvent;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.jobs.RecoveryReport;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Picking the bench back up after a worker died, against the database.
 *
 * <p>The rules for what to do about an interrupted job are not here. They are in {@link
 * io.github.rkaushikdev.carrierworks.jobs.Recovery}, written against the in-memory bench, so that
 * what happens to a job does not depend on which entry point somebody used.
 *
 * <p>What is here is the part that genuinely differs: the adapters that let those rules read and
 * write the database, the retry limit out of the settings, and the audit lines, which the in-memory
 * bench has nowhere to write.
 */
public final class Recovery {

    private static final String ACTOR = "recovery";

    private Recovery() {}

    /**
     * Every job the database calls running that the journal never closed, longest running first.
     *
     * <p>A job the journal has never heard of counts as interrupted too. That is the crash between
     * writing the row and writing the first journal line, and it is rare, but the answer to it is the
     * same.
     */
    public static List<ProgramJob> interrupted(UnitOfWork unit, JobJournal journal) {
        Set<String> open = Set.copyOf(journal.unfinished());
        Set<String> seen = journal.entries().stream().map(JournalEntry::job).collect(Collectors.toSet());
        return unit.jobs().running().stream()
                .filter(job -> open.contains(job.id()) || !seen.contains(job.id()))
                .toList();
    }

    /** {@link #recover(UnitOfWork, JobJournal, Instant, BenchSettings)} on the default settings. */
    public static RecoveryReport recover(UnitOfWork unit, JobJournal journal, Instant now) {
        return recover(unit, journal, now, BenchSettings.defaults());
    }

    /**
     * Close out every interrupted job, and write down what that cost.
     *
     * <p>Safe to run at any time, and meant to run at start up. A job that is genuinely in flight
     * looks exactly like one that was abandoned, so the caller is the thing that knows nothing else is
     * running. A job the journal never saw gets its missing start line first, so the rules see it
     * like any other. Jobs with attempts left under the settings' limit go back on the queue.
     */
    public static RecoveryReport recover(UnitOfWork unit, JobJournal journal, Instant now, BenchSettings settings) {
        Set<String> open = Set.copyOf(journal.unfinished());
        for (ProgramJob job : interrupted(unit, journal)) {
            if (!open.contains(job.id())) {
                journal.append(
                        job.id(),
                        JournalEvent.STARTED,
                        now,
                        Details.of("board", job.board(), "mode", job.mode().value()));
            }
        }
        RecoveryReport report = io.github.rkaushikdev.carrierworks.jobs.Recovery.recover(
                journal,
                new JobLedgerOverRepository(unit),
                new BookOverRepository(unit),
                new LeaseStoreOverRepository(unit),
                now,
                settings.programAttempts());
        for (String board : report.boardsFaulted()) {
            note(unit, now, "faulted", board, "a flash write was interrupted by a restart");
        }
        for (String board : report.boardsReturned()) {
            note(unit, now, "returned", board, "put back after an interrupted load");
        }
        for (String jobId : report.jobsRequeued()) {
            note(unit, now, "requeued", jobId, "interrupted part way through a load");
        }
        for (String jobId : report.jobsClosed()) {
            note(unit, now, "closed", jobId, "the bench restarted during this job");
        }
        return report;
    }

    private static void note(UnitOfWork unit, Instant now, String action, String subject, String detail) {
        unit.audit().add(new AuditEntry(now, ACTOR, action, subject, Audit.trimmed(detail), ""));
    }
}
