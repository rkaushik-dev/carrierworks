package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Drivers;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactStore;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.FaultLog;
import io.github.rkaushikdev.carrierworks.jobs.BoardDrivers;
import io.github.rkaushikdev.carrierworks.jobs.JobJournal;
import io.github.rkaushikdev.carrierworks.jobs.JobRunner;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.jobs.RunResult;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.reports.TimingClosure;
import java.time.Instant;
import java.util.Optional;

/**
 * Wiring a job to a board and running it, in one place instead of two.
 *
 * <p>The runner takes five collaborators and none of them are optional, which is right for the
 * runner and wrong for every caller: the command line and the worker were assembling the same five
 * the same way, and the second one to be written quietly forgot to pass the timing report, so jobs
 * that should have been gated on timing closure were not.
 *
 * <p>So the assembly lives here, once. What a caller chooses is whether to use the real cable or
 * the simulator, and everything else follows from the job and the settings.
 */
public final class Execution {

    /** The file in the journal folder the whole bench appends to. */
    public static final String JOURNAL_FILE = "jobs.jsonl";

    private Execution() {}

    /** The one journal file the whole bench appends to. */
    public static JobJournal journalFor(BenchSettings settings) {
        return new JobJournal(settings.journalRoot().resolve(JOURNAL_FILE));
    }

    /** The next job worth attempting, or nothing when the queue is quiet. */
    public static Optional<ProgramJob> nextReady(UnitOfWork unit, Instant now) {
        return Programming.nextReady(unit, now);
    }

    /** Run a job against the simulator, on the default settings. */
    public static RunResult runJob(UnitOfWork unit, ProgramJob job, Instant now) {
        return runJob(unit, job, now, BenchSettings.defaults(), false);
    }

    /** Run a job with the bench's own journal and a fresh fault log. */
    public static RunResult runJob(UnitOfWork unit, ProgramJob job, Instant now, BenchSettings settings, boolean live) {
        return runJob(unit, job, now, settings, journalFor(settings), new FaultLog(), live);
    }

    /**
     * Run one job against a board, and save whatever it came to.
     *
     * <p>The board is read once and handed to both drivers, so a job whose board row changes
     * underneath it still talks to the board it was gated against. The job is gated on the image's
     * stored timing report when there is one. A job that will be retried is saved held until now,
     * which puts it back on the ready list straight away.
     *
     * @param live whether to talk to the board's real cable rather than the simulator
     */
    public static RunResult runJob(
            UnitOfWork unit,
            ProgramJob job,
            Instant now,
            BenchSettings settings,
            JobJournal journal,
            FaultLog faults,
            boolean live) {
        Board board = unit.boards().get(job.board());
        Optional<Boolean> closed = Reports.timingFor(unit, job.digest()).map(TimingClosure::closed);
        JobRunner runner = new JobRunner(
                new ArtifactStore(settings.artifactRoot()),
                new BookOverRepository(unit),
                new LeaseStoreOverRepository(unit),
                journal,
                faults,
                settings);
        BoardDrivers drivers =
                BoardDrivers.of(tag -> Drivers.sessionFor(board, !live), tag -> Drivers.flashFor(board, !live));
        RunResult result = runner.run(job, now, drivers, closed);
        unit.jobs().save(result.job(), result.willRetry() ? Optional.of(now) : Optional.empty());
        return result;
    }
}
