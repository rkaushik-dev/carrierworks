package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.BenchError;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.BoardRegistry;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.LeaseRegistry;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.TransientBenchError;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactStore;
import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import io.github.rkaushikdev.carrierworks.flash.EraseUnit;
import io.github.rkaushikdev.carrierworks.flash.FlashLayout;
import io.github.rkaushikdev.carrierworks.flash.FlashPlan;
import io.github.rkaushikdev.carrierworks.flash.WearLedger;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.inventory.FaultKind;
import io.github.rkaushikdev.carrierworks.inventory.FaultLog;
import io.github.rkaushikdev.carrierworks.jtag.ScanSession;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Runs one programming job at a time against whatever the bench hands it: gates, attempt, journal,
 * and what to do after.
 *
 * <p>The runner is the only place in the bench that knows the whole sequence, and it is written so
 * that every path through it leaves three things behind: a journal line, a job record with its
 * state, and a board in a state somebody can act on. The journal line for a step is written before
 * the step is attempted.
 *
 * <p>Retries are the part worth reading. A transient failure puts the job back on the queue and
 * records a fault against the board, so a board that keeps producing transient failures ends up
 * quarantined by the fault policy rather than by anything here. A permanent failure ends the job.
 * And a failure while the board is in an unknown state, which is anything that goes wrong during a
 * flash write, faults the board as well, because a half written flash is not a board to hand to the
 * next person.
 *
 * <p>The split between the two halves of the flash is the lab's convention and it lives here rather
 * than on the board: the golden image gets the bottom half of whatever part is fitted, the update
 * image gets the top half, and the warm boot address is therefore known without anybody writing it
 * down twice.
 */
public final class JobRunner {

    private static final String RUNNER = "runner";

    private final ArtifactStore store;
    private final BoardRegistry book;
    private final LeaseRegistry leases;
    private final JobJournal journal;
    private final FaultLog faults;
    private final BenchSettings settings;
    private final Optional<WearLedger> wear;

    /** A runner on the default settings that keeps no wear count. */
    public JobRunner(
            ArtifactStore store, BoardRegistry book, LeaseRegistry leases, JobJournal journal, FaultLog faults) {
        this(store, book, leases, journal, faults, BenchSettings.defaults(), Optional.empty());
    }

    /** A runner on these settings that keeps no wear count. */
    public JobRunner(
            ArtifactStore store,
            BoardRegistry book,
            LeaseRegistry leases,
            JobJournal journal,
            FaultLog faults,
            BenchSettings settings) {
        this(store, book, leases, journal, faults, settings, Optional.empty());
    }

    /**
     * A runner on these settings that counts every flash write's erases in a wear ledger, and
     * refuses the golden image to a worn part.
     */
    public JobRunner(
            ArtifactStore store,
            BoardRegistry book,
            LeaseRegistry leases,
            JobJournal journal,
            FaultLog faults,
            BenchSettings settings,
            WearLedger wear) {
        this(store, book, leases, journal, faults, settings, Optional.of(wear));
    }

    private JobRunner(
            ArtifactStore store,
            BoardRegistry book,
            LeaseRegistry leases,
            JobJournal journal,
            FaultLog faults,
            BenchSettings settings,
            Optional<WearLedger> wear) {
        this.store = Objects.requireNonNull(store, "store");
        this.book = Objects.requireNonNull(book, "book");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.faults = Objects.requireNonNull(faults, "faults");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.wear = wear;
    }

    /** Golden in the bottom half of the part, update in the top half. */
    public static FlashLayout flashLayout(FlashSpec spec) {
        long half = spec.capacityBytes() / 2;
        return FlashLayout.multiboot(spec, half, half, half);
    }

    /** The settings the runner gates and retries by. */
    public BenchSettings settings() {
        return settings;
    }

    /** The wear ledger flash writes are counted in, if the runner keeps one. */
    public Optional<WearLedger> wear() {
        return wear;
    }

    /** How long to wait before the next attempt, growing with each one. */
    public int backoffSeconds(int attempt) {
        return settings.programBackoffSeconds() * Math.max(attempt, 1);
    }

    /** {@link #run(ProgramJob, Instant, BoardDrivers, Optional)} with no timing report read for the image. */
    public RunResult run(ProgramJob job, Instant now, BoardDrivers drivers) {
        return run(job, now, drivers, Optional.empty());
    }

    /**
     * One attempt at one job.
     *
     * <p>A job the gates refuse is failed with the code {@code gate} and never touches the board.
     * Anything the bench raises on purpose while the job is on the board fails the attempt, and the
     * result says whether it will be retried.
     *
     * @param timingClosed whether the image's build report says timing closed, or empty when none
     *     has been read
     * @throws NotFound when the board or the image is not known at all
     */
    public RunResult run(ProgramJob job, Instant now, BoardDrivers drivers, Optional<Boolean> timingClosed) {
        Board board = book.board(job.board());
        ArtifactRecord record = store.recordFor(job.digest());
        GateReport report = Gates.check(job, board, record, book.state(job.board()))
                .lease(leases.forBoard(job.board(), now))
                .timingClosed(timingClosed)
                .requireTiming(settings.requireTimingClosure())
                .wear(wear)
                .report();
        if (!report.allowed()) {
            String reason = String.join("; ", report.blockers());
            journal.append(job.id(), JournalEvent.FAILED, now, Details.of("board", job.board(), "reason", reason));
            return new RunResult(job.start(now).failed(now, reason, "gate"), reason);
        }
        return attempt(job, record, now, drivers);
    }

    private RunResult attempt(ProgramJob job, ArtifactRecord record, Instant now, BoardDrivers drivers) {
        Optional<Lease> lease = leases.forBoard(job.board(), now);
        ProgramJob running = job.start(now, lease.map(Lease::id).orElse(""));
        journal.append(
                running.id(),
                JournalEvent.STARTED,
                now,
                Details.of("board", running.board(), "mode", running.mode().value()));
        book.moveTo(running.board(), BoardStatus.PROGRAMMING, now, "", running.requestedBy());
        byte[] image = store.read(record.digest());
        String detail;
        try {
            detail = carryOut(running, image, drivers);
        } catch (BenchError failure) {
            return failed(running, failure, now);
        }
        journal.append(running.id(), JournalEvent.PASSED, now, Details.of("board", running.board()));
        book.moveTo(running.board(), BoardStatus.LEASED, now, "", running.requestedBy());
        return new RunResult(running.passed(now), detail);
    }

    private String carryOut(ProgramJob job, byte[] image, BoardDrivers drivers) {
        Board board = book.board(job.board());
        if (job.mode() == JobMode.JTAG) {
            Function<String, ScanSession> sessions =
                    drivers.sessions().orElseThrow(() -> new NotFound("transport", job.board()));
            ScanSession session = sessions.apply(job.board());
            return Programmer.loadOverJtag(session, image, JtagLoad.defaults().withBoard(job.board()))
                    .describe();
        }
        Function<String, SpiFlashDriver> flashes =
                drivers.flashes().orElseThrow(() -> new NotFound("flash driver", job.board()));
        FlashLayout layout = flashLayout(board.flashSpec());
        String region = job.touchesGolden() ? "golden" : "update";
        FlashPlan plan = FlashPlan.forWrite(layout, region, image, EraseUnit.SECTOR, job.verify());
        wear.ifPresent(ledger -> ledger.record(job.board(), plan));
        FlashOutcome written = Flasher.writeImage(
                flashes.apply(job.board()), plan, image, layout.region(region).start());
        return written.describe();
    }

    private RunResult failed(ProgramJob job, BenchError failure, Instant now) {
        boolean retryable = failure instanceof TransientBenchError && !job.exhausted(settings.programAttempts());
        journal.append(
                job.id(),
                JournalEvent.FAILED,
                now,
                Details.of("board", job.board(), "reason", failure.message(), "code", failure.code()));
        faults.add(job.board(), faultKind(failure.code()), now, failure.message(), job.id());
        boolean unknown = job.writesFlash();
        if (unknown) {
            book.moveTo(
                    job.board(),
                    BoardStatus.FAULTED,
                    now,
                    job.mode().value() + " failed part way: " + failure.message(),
                    RUNNER);
        } else {
            book.moveTo(job.board(), BoardStatus.LEASED, now, "", job.requestedBy());
        }
        ProgramJob beaten = job.failed(now, failure.message(), failure.code(), retryable);
        return new RunResult(beaten, failure.message(), unknown, retryable ? backoffSeconds(job.attempts()) : 0);
    }

    /** The fault kind the quarantine policy counts an error under, by the error's code. */
    private static FaultKind faultKind(String code) {
        return switch (code) {
            case "chain" -> FaultKind.CHAIN;
            case "programming" -> FaultKind.PROGRAM;
            case "verify" -> FaultKind.VERIFY;
            case "power" -> FaultKind.POWER;
            default -> FaultKind.OTHER;
        };
    }
}
