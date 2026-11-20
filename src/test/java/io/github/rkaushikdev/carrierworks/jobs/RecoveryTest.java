package io.github.rkaushikdev.carrierworks.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.inventory.BoardBook;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.leases.LeaseStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryTest {

    private static final Instant NOW = Instant.parse("2025-11-01T07:00:00Z");
    private static final String DIGEST = "ab".repeat(32);

    @TempDir
    Path tmp;

    /** Two boards with neither a cable nor an outlet, a job store, a journal and a lease store. */
    private static final class Bench {

        final BoardBook book = new BoardBook(NOW.minus(Duration.ofDays(2)));
        final JobStore jobs = new JobStore();
        final JobJournal journal;
        final LeaseStore leases = new LeaseStore();

        Bench(Path tmp) {
            book.add(BoardSetup.kintex("bx-14").bare().build());
            book.add(BoardSetup.artix("bx-15").bare().build());
            journal = new JobJournal(tmp.resolve("journal.jsonl"));
        }

        ProgramJob startJob() {
            return startJob("bx-14", JobMode.JTAG);
        }

        ProgramJob startJob(JobMode mode) {
            return startJob("bx-14", mode);
        }

        /** A job ten minutes into running on a board leased for two hours, as a crash would leave it. */
        ProgramJob startJob(String board, JobMode mode) {
            Instant moment = NOW.minus(Duration.ofMinutes(10));
            Lease lease = leases.acquire(board, "team-a", "capture", moment, 120);
            book.moveTo(board, BoardStatus.LEASED, moment, "", "team-a");
            ProgramJob job = jobs.submit(board, DIGEST, mode, "team-a", moment);
            jobs.update(job.start(moment, lease.id()));
            journal.append(job.id(), JournalEvent.STARTED, moment, Details.of("board", board, "mode", mode.value()));
            book.moveTo(board, BoardStatus.PROGRAMMING, moment, "", "team-a");
            return job;
        }

        RecoveryReport recover() {
            return recover(NOW);
        }

        RecoveryReport recover(Instant when) {
            return Recovery.recover(journal, jobs, book, leases, when);
        }
    }

    @Test
    void aCleanBenchRecoversToNothing() {
        RecoveryReport report = new Bench(tmp).recover();
        assertThat(report.clean()).isTrue();
        assertThat(report.describe()).isEqualTo("nothing was left half done");
    }

    @Test
    void anInterruptedJtagJobIsClosedAndTheBoardComesBack() {
        Bench bench = new Bench(tmp);
        ProgramJob job = bench.startJob();
        RecoveryReport report = bench.recover();
        assertThat(report.jobsClosed()).containsExactly(job.id());
        assertThat(bench.jobs.job(job.id()).state()).isEqualTo(JobState.FAILED);
        assertThat(bench.jobs.job(job.id()).errorCode()).isEqualTo("abandoned");
        assertThat(bench.book.state("bx-14").state()).isEqualTo(BoardStatus.LEASED);
    }

    @Test
    void aBoardWhoseLeaseExpiredGoesAllTheWayBack() {
        Bench bench = new Bench(tmp);
        bench.startJob();
        RecoveryReport report = bench.recover(NOW.plus(Duration.ofHours(4)));
        assertThat(report.boardsReturned()).containsExactly("bx-14");
        assertThat(bench.book.state("bx-14").usable()).isTrue();
    }

    @Test
    void anInterruptedFlashJobFaultsTheBoard() {
        Bench bench = new Bench(tmp);
        ProgramJob job = bench.startJob(JobMode.FLASH_UPDATE);
        RecoveryReport report = bench.recover();
        assertThat(report.boardsFaulted()).containsExactly("bx-14");
        assertThat(bench.book.state("bx-14").state()).isEqualTo(BoardStatus.FAULTED);
        assertThat(bench.book.state("bx-14").reason()).contains("interrupted");
        assertThat(bench.jobs.job(job.id()).state()).isEqualTo(JobState.FAILED);
    }

    @Test
    void aFaultedBoardGivesItsLeaseBack() {
        Bench bench = new Bench(tmp);
        bench.startJob(JobMode.FLASH_GOLDEN);
        bench.recover();
        assertThat(bench.leases.forBoard("bx-14", NOW)).isEmpty();
    }

    @Test
    void theAbandonmentIsJournalled() {
        Bench bench = new Bench(tmp);
        ProgramJob job = bench.startJob();
        bench.recover();
        assertThat(bench.journal.lastEvent(job.id()).orElseThrow().event()).isEqualTo(JournalEvent.ABANDONED);
        assertThat(bench.journal.unfinished()).isEmpty();
    }

    @Test
    void recoveryRunsTwiceAndFindsNothingTheSecondTime() {
        Bench bench = new Bench(tmp);
        bench.startJob();
        bench.recover();
        assertThat(bench.recover().clean()).isTrue();
    }

    @Test
    void twoBoardsAreBothSettled() {
        Bench bench = new Bench(tmp);
        bench.startJob("bx-14", JobMode.JTAG);
        bench.startJob("bx-15", JobMode.FLASH_UPDATE);
        RecoveryReport report = bench.recover();
        assertThat(report.jobsClosed()).hasSize(2);
        assertThat(report.boardsFaulted()).containsExactly("bx-15");
        assertThat(bench.book.state("bx-14").state()).isEqualTo(BoardStatus.LEASED);
    }

    @Test
    void aBoardLeftProgrammingWithNoJobIsSettledToo() {
        Bench bench = new Bench(tmp);
        bench.leases.acquire("bx-14", "team-a", "capture", NOW.minus(Duration.ofMinutes(5)), 120);
        bench.book.moveTo("bx-14", BoardStatus.LEASED, NOW.minus(Duration.ofMinutes(5)), "", "team-a");
        bench.book.moveTo("bx-14", BoardStatus.PROGRAMMING, NOW.minus(Duration.ofMinutes(4)), "", "team-a");
        RecoveryReport report = bench.recover();
        assertThat(report.boardsReturned()).containsExactly("bx-14");
        assertThat(bench.book.state("bx-14").state()).isEqualTo(BoardStatus.LEASED);
    }

    @Test
    void aJournalLineForAJobNobodyHasIsReportedRatherThanIgnored() {
        Bench bench = new Bench(tmp);
        bench.journal.append(
                "job-09999", JournalEvent.STARTED, NOW.minus(Duration.ofMinutes(5)), Details.of("board", "bx-14"));
        RecoveryReport report = bench.recover();
        assertThat(report.unknownJobs()).containsExactly("job-09999");
        assertThat(bench.journal.unfinished()).isEmpty();
    }

    @Test
    void aReportDescribesWhatItDid() {
        Bench bench = new Bench(tmp);
        bench.startJob("bx-15", JobMode.FLASH_UPDATE);
        String described = bench.recover().describe();
        assertThat(described).contains("closed job-00001");
        assertThat(described).contains("faulted bx-15");
    }

    @Test
    void aJobThatAlreadyFailedIsNotClosedAgain() {
        Bench bench = new Bench(tmp);
        ProgramJob job = bench.startJob();
        bench.jobs.update(bench.jobs.job(job.id()).failed(NOW, "cable", "", false));
        RecoveryReport report = bench.recover();
        assertThat(report.jobsClosed()).isEmpty();
        assertThat(bench.jobs.job(job.id()).error()).isEqualTo("cable");
    }
}
