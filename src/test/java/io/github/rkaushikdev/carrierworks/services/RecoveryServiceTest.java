package io.github.rkaushikdev.carrierworks.services;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.AuditEntry;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactStore;
import io.github.rkaushikdev.carrierworks.bitstream.Images;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.jobs.JobJournal;
import io.github.rkaushikdev.carrierworks.jobs.JobMode;
import io.github.rkaushikdev.carrierworks.jobs.JobState;
import io.github.rkaushikdev.carrierworks.jobs.JournalEntry;
import io.github.rkaushikdev.carrierworks.jobs.JournalEvent;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.jobs.RecoveryReport;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryServiceTest {

    private static final Instant WHEN = Instant.parse("2026-04-24T03:00:00Z");
    private static final Instant CRASH = WHEN.minus(Duration.ofMinutes(4));

    @TempDir
    Path workspace;

    private JobJournal journal;
    private BenchDatabase database;

    @BeforeEach
    void open() {
        journal = new JobJournal(workspace.resolve("jobs.jsonl"));
        database = BenchDatabase.inMemory();
    }

    @AfterEach
    void close() {
        database.close();
    }

    /** Run a case in one unit of work, on a bench holding bx-14 and bx-15. */
    private void onBench(Consumer<UnitOfWork> body) {
        database.run(unit -> {
            Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), WHEN.minus(Duration.ofDays(1)));
            Inventory.addBoard(unit, BoardSetup.artix("bx-15").build(), WHEN.minus(Duration.ofDays(1)));
            body.accept(unit);
        });
    }

    /** The shared image, stored in the qual channel, by its digest. */
    private String ingest(UnitOfWork unit) {
        ArtifactStore store = new ArtifactStore(workspace.resolve("artifacts"));
        return Registry.ingest(unit, store, Images.bitstream(), WHEN, "qual").digest();
    }

    private ProgramJob inFlight(UnitOfWork unit, String digest) {
        return inFlight(unit, digest, "bx-14", JobMode.JTAG, true, 1);
    }

    private ProgramJob inFlight(UnitOfWork unit, String digest, JobMode mode) {
        return inFlight(unit, digest, "bx-14", mode, true, 1);
    }

    /** A job the database thinks is running, as a crash would have left it. */
    private ProgramJob inFlight(
            UnitOfWork unit, String digest, String board, JobMode mode, boolean started, int attempts) {
        Leasing.acquire(unit, board, "team-a", "capture", CRASH.minus(Duration.ofMinutes(1)));
        ProgramJob job = Programming.submit(unit, board, digest, mode, "team-a", CRASH);
        ProgramJob running = job;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            running = running.start(CRASH);
            if (attempt < attempts) {
                running = running.failed(CRASH, "transient", "chain", true);
            }
        }
        unit.jobs().save(running);
        Inventory.moveState(unit, board, BoardStatus.PROGRAMMING, CRASH, "", "team-a");
        if (started) {
            journal.append(job.id(), JournalEvent.STARTED, CRASH, Details.of("board", board, "mode", mode.value()));
        }
        return running;
    }

    @Test
    void aBenchWithNothingRunningHasNothingToRecover() {
        onBench(unit -> {
            assertThat(Recovery.interrupted(unit, journal)).isEmpty();
            assertThat(Recovery.recover(unit, journal, WHEN).clean()).isTrue();
        });
    }

    @Test
    void aRunningJobWithAnOpenJournalLineIsInterrupted() {
        onBench(unit -> {
            ProgramJob job = inFlight(unit, ingest(unit));
            assertThat(Recovery.interrupted(unit, journal))
                    .extracting(ProgramJob::id)
                    .containsExactly(job.id());
        });
    }

    @Test
    void aRunningJobTheJournalNeverSawIsInterruptedToo() {
        onBench(unit -> {
            ProgramJob job = inFlight(unit, ingest(unit), "bx-14", JobMode.JTAG, false, 1);
            assertThat(Recovery.interrupted(unit, journal))
                    .extracting(ProgramJob::id)
                    .containsExactly(job.id());
        });
    }

    @Test
    void aJobTheJournalClosedIsNotInterrupted() {
        onBench(unit -> {
            ProgramJob job = inFlight(unit, ingest(unit));
            journal.append(job.id(), JournalEvent.PASSED, CRASH, Details.of("board", "bx-14"));
            assertThat(Recovery.interrupted(unit, journal)).isEmpty();
        });
    }

    @Test
    void anInterruptedJtagLoadGoesBackOnTheQueue() {
        onBench(unit -> {
            ProgramJob job = inFlight(unit, ingest(unit));
            RecoveryReport report = Recovery.recover(unit, journal, WHEN);
            assertThat(report.jobsRequeued()).containsExactly(job.id());
            assertThat(unit.jobs().get(job.id()).state()).isEqualTo(JobState.QUEUED);
        });
    }

    @Test
    void aRequeuedJobLeavesTheBoardUsable() {
        onBench(unit -> {
            inFlight(unit, ingest(unit));
            Recovery.recover(unit, journal, WHEN);
            assertThat(unit.boards().state("bx-14").state()).isEqualTo(BoardStatus.LEASED);
        });
    }

    @Test
    void aRequeuedJobOnABoardNobodyHoldsGoesBackToAvailable() {
        onBench(unit -> {
            ProgramJob job = inFlight(unit, ingest(unit));
            Lease held = unit.leases().forBoard("bx-14", WHEN).orElseThrow();
            Leasing.release(unit, held.id(), CRASH, "gave it back");
            Recovery.recover(unit, journal, WHEN);
            assertThat(unit.boards().state("bx-14").state()).isEqualTo(BoardStatus.AVAILABLE);
            assertThat(unit.jobs().get(job.id()).state()).isEqualTo(JobState.QUEUED);
        });
    }

    @Test
    void anInterruptedFlashWriteFaultsTheBoard() {
        onBench(unit -> {
            ProgramJob job = inFlight(unit, ingest(unit), JobMode.FLASH_UPDATE);
            RecoveryReport report = Recovery.recover(unit, journal, WHEN);
            assertThat(report.boardsFaulted()).containsExactly("bx-14");
            assertThat(unit.boards().state("bx-14").state()).isEqualTo(BoardStatus.FAULTED);
            assertThat(unit.jobs().get(job.id()).state()).isEqualTo(JobState.FAILED);
        });
    }

    @Test
    void aFaultedBoardGivesItsLeaseBack() {
        onBench(unit -> {
            inFlight(unit, ingest(unit), JobMode.FLASH_GOLDEN);
            Recovery.recover(unit, journal, WHEN);
            assertThat(unit.leases().forBoard("bx-14", WHEN)).isEmpty();
        });
    }

    @Test
    void aJobOutOfAttemptsIsNotRequeued() {
        onBench(unit -> {
            ProgramJob job = inFlight(unit, ingest(unit), "bx-14", JobMode.JTAG, true, 3);
            RecoveryReport report = Recovery.recover(unit, journal, WHEN);
            assertThat(report.jobsClosed()).containsExactly(job.id());
            assertThat(unit.jobs().get(job.id()).state()).isEqualTo(JobState.FAILED);
            assertThat(unit.boards().state("bx-14").state()).isEqualTo(BoardStatus.LEASED);
        });
    }

    @Test
    void theAttemptLimitComesFromTheSettings() {
        onBench(unit -> {
            ProgramJob job = inFlight(unit, ingest(unit), "bx-14", JobMode.JTAG, true, 2);
            BenchSettings settings = BenchSettings.builder().programAttempts(2).build();
            RecoveryReport report = Recovery.recover(unit, journal, WHEN, settings);
            assertThat(report.jobsClosed()).containsExactly(job.id());
        });
    }

    @Test
    void recoveryClosesTheJournalLine() {
        onBench(unit -> {
            ProgramJob job = inFlight(unit, ingest(unit));
            Recovery.recover(unit, journal, WHEN);
            assertThat(journal.lastEvent(job.id())).map(JournalEntry::event).contains(JournalEvent.ABANDONED);
            assertThat(Recovery.interrupted(unit, journal)).isEmpty();
        });
    }

    @Test
    void recoveryLeavesALineInTheAuditTrail() {
        onBench(unit -> {
            ProgramJob job = inFlight(unit, ingest(unit));
            Recovery.recover(unit, journal, WHEN);
            assertThat(unit.audit().forSubject(job.id()))
                    .filteredOn(entry -> entry.actor().equals("recovery"))
                    .extracting(AuditEntry::action)
                    .containsExactly("requeued");
            assertThat(unit.audit().forSubject("bx-14"))
                    .filteredOn(entry -> entry.actor().equals("recovery"))
                    .extracting(AuditEntry::action)
                    .contains("returned");
        });
    }

    @Test
    void twoInterruptedJobsAreBothDealtWith() {
        onBench(unit -> {
            String digest = ingest(unit);
            ProgramJob first = inFlight(unit, digest, "bx-14", JobMode.JTAG, true, 1);
            inFlight(unit, digest, "bx-15", JobMode.FLASH_UPDATE, true, 1);
            RecoveryReport report = Recovery.recover(unit, journal, WHEN);
            assertThat(report.jobsRequeued()).containsExactly(first.id());
            assertThat(report.boardsFaulted()).containsExactly("bx-15");
            assertThat(report.boardsReturned()).containsExactly("bx-14");
        });
    }

    @Test
    void runningRecoveryTwiceChangesNothingTheSecondTime() {
        onBench(unit -> {
            inFlight(unit, ingest(unit));
            Recovery.recover(unit, journal, WHEN);
            assertThat(Recovery.recover(unit, journal, WHEN.plus(Duration.ofMinutes(1)))
                            .clean())
                    .isTrue();
        });
    }

    @Test
    void theReportReadsAsOneLine() {
        onBench(unit -> {
            inFlight(unit, ingest(unit));
            assertThat(Recovery.recover(unit, journal, WHEN).describe()).contains("requeued job-00001");
        });
    }
}
