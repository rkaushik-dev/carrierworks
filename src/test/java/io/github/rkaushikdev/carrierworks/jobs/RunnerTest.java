package io.github.rkaushikdev.carrierworks.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactStore;
import io.github.rkaushikdev.carrierworks.bitstream.Bitstream;
import io.github.rkaushikdev.carrierworks.bitstream.Images;
import io.github.rkaushikdev.carrierworks.flash.WearLedger;
import io.github.rkaushikdev.carrierworks.inventory.BoardBook;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.inventory.FaultKind;
import io.github.rkaushikdev.carrierworks.inventory.FaultLog;
import io.github.rkaushikdev.carrierworks.jtag.ChainSimulator;
import io.github.rkaushikdev.carrierworks.jtag.ScanChain;
import io.github.rkaushikdev.carrierworks.jtag.ScanSession;
import io.github.rkaushikdev.carrierworks.leases.LeaseStore;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunnerTest {

    private static final Instant NOW = Instant.parse("2025-10-24T09:00:00Z");

    @TempDir
    Path tmp;

    /** A full Kintex image with 64 frame words, read from a {@code .bit} file. */
    private static Bitstream image() {
        byte[] bitfile = Images.image()
                .part(Images.KINTEX_PART)
                .date("2025/10/20")
                .time("02:00:00")
                .frames(64)
                .bitfile();
        return Bitstream.read(bitfile, "radar_frontend.bit");
    }

    private Bench bench() {
        return new Bench(tmp, "qual", false, false, Optional.empty());
    }

    private Bench bench(String channel) {
        return new Bench(tmp, channel, false, false, Optional.empty());
    }

    /** A bench whose device takes the image and never comes up. */
    private Bench deafBench() {
        return new Bench(tmp, "qual", true, false, Optional.empty());
    }

    /** A bench whose flash has a weak byte where the job writes. */
    private Bench weakBench() {
        return new Bench(tmp, "qual", false, true, Optional.empty());
    }

    /**
     * One Kintex board leased to team-a, a store holding one image, a flash part beside the board,
     * and a runner on the default settings.
     */
    private static final class Bench {

        final BoardBook book = new BoardBook(NOW.minus(Duration.ofDays(1)));
        final ArtifactStore store;
        final ArtifactRecord record;
        final LeaseStore leases = new LeaseStore();
        final JobJournal journal;
        final FaultLog faults = new FaultLog();
        final FakeFlash flash;
        final boolean deaf;
        final JobRunner runner;

        Bench(Path tmp, String channel, boolean deaf, boolean weak, Optional<WearLedger> wear) {
            book.add(BoardSetup.kintex("bx-14").serial("210308AB1234").outlet(7).build());
            store = new ArtifactStore(tmp.resolve("artifacts"));
            record = store.put(image(), channel);
            journal = new JobJournal(tmp.resolve("journal.jsonl"));
            flash = new FakeFlash(book.board("bx-14").flashSpec());
            if (weak) {
                // a byte inside the update region, which is where the job writes
                flash.setWeakBytes(Set.of(flash.spec().capacityBytes() / 2 + 16));
            }
            this.deaf = deaf;
            BenchSettings settings = BenchSettings.defaults();
            if (wear.isPresent()) {
                runner = new JobRunner(store, book, leases, journal, faults, settings, wear.get());
            } else {
                runner = new JobRunner(store, book, leases, journal, faults, settings);
            }
            leases.acquire("bx-14", "team-a", "capture", NOW);
            book.moveTo("bx-14", BoardStatus.LEASED, NOW, "", "team-a");
        }

        ScanSession sessionFor(String board) {
            ScanChain chain = ScanChain.ofDevices("xc7k325t");
            ChainSimulator sim = deaf
                    ? new ChainSimulator(List.of(new DeafDevice("u1", 0x03651093L, 6)))
                    : ChainSimulator.forChain(chain);
            return new ScanSession(sim, chain);
        }

        SpiFlashDriver flashFor(String board) {
            return flash;
        }

        BoardDrivers drivers() {
            return BoardDrivers.of(this::sessionFor, this::flashFor);
        }

        ProgramJob job() {
            return job(JobMode.JTAG);
        }

        ProgramJob job(JobMode mode) {
            return job(mode, record.digest());
        }

        ProgramJob job(JobMode mode, String digest) {
            return ProgramJob.builder("job-00001", "bx-14", digest, mode, "team-a", NOW)
                    .build();
        }

        RunResult run() {
            return run(job());
        }

        RunResult run(ProgramJob job) {
            return runner.run(job, NOW, drivers());
        }

        RunResult run(ProgramJob job, boolean timingClosed) {
            return runner.run(job, NOW, drivers(), Optional.of(timingClosed));
        }
    }

    @Test
    void aGoodJtagJobPasses() {
        Bench bench = bench();
        RunResult result = bench.run();
        assertThat(result.passed()).isTrue();
        assertThat(result.detail()).contains("done");
        assertThat(bench.book.state("bx-14").state()).isEqualTo(BoardStatus.LEASED);
    }

    @Test
    void aPassIsJournalledWithTheBoard() {
        Bench bench = bench();
        bench.run();
        List<JournalEvent> events = bench.journal.forJob("job-00001").stream()
                .map(JournalEntry::event)
                .toList();
        assertThat(events).containsExactly(JournalEvent.STARTED, JournalEvent.PASSED);
        assertThat(bench.journal.unfinished()).isEmpty();
    }

    @Test
    void theBoardIsMarkedProgrammingWhileTheJobRuns() {
        Bench bench = bench();
        List<BoardStatus> seen = new ArrayList<>();
        Function<String, ScanSession> watching = board -> {
            seen.add(bench.book.state(board).state());
            return bench.sessionFor(board);
        };
        bench.runner.run(bench.job(), NOW, BoardDrivers.of(watching, bench::flashFor));
        assertThat(seen).containsExactly(BoardStatus.PROGRAMMING);
    }

    @Test
    void aJobThatFailsItsGatesNeverTouchesTheBoard() {
        Bench bench = bench("dev");
        RunResult result = bench.run(bench.job(JobMode.FLASH_UPDATE), true);
        assertThat(result.passed()).isFalse();
        assertThat(result.willRetry()).isFalse();
        assertThat(bench.book.state("bx-14").state()).isEqualTo(BoardStatus.LEASED);
        assertThat(result.detail()).contains("takes qual or golden");
    }

    @Test
    void aTransientFailureComesBackAround() {
        Bench bench = deafBench();
        RunResult result = bench.run();
        assertThat(result.willRetry()).isTrue();
        assertThat(result.retryInSeconds()).isEqualTo(15);
        assertThat(bench.faults.count("bx-14", NOW, Duration.ofHours(1))).isEqualTo(1);
    }

    @Test
    void theBackoffGrowsWithTheAttempt() {
        Bench bench = deafBench();
        assertThat(bench.runner.backoffSeconds(1)).isEqualTo(15);
        assertThat(bench.runner.backoffSeconds(3)).isEqualTo(45);
    }

    @Test
    void aJobThatRunsOutOfAttemptsStopsRetrying() {
        Bench bench = deafBench();
        RunResult result = bench.run(bench.job());
        for (int attempt = 2; attempt <= 3; attempt++) {
            result = bench.run(result.job());
        }
        ProgramJob job = result.job();
        assertThat(job.state()).isEqualTo(JobState.FAILED);
        assertThat(job.attempts()).isEqualTo(3);
        assertThat(result.willRetry()).isFalse();
    }

    @Test
    void aFailedJtagJobLeavesTheBoardLeasedRatherThanFaulted() {
        Bench bench = deafBench();
        bench.run();
        assertThat(bench.book.state("bx-14").state()).isEqualTo(BoardStatus.LEASED);
    }

    @Test
    void aFlashJobWritesAndVerifies() {
        Bench bench = bench();
        RunResult result = bench.run(bench.job(JobMode.FLASH_UPDATE), true);
        assertThat(result.passed()).isTrue();
        assertThat(result.detail()).contains("verified");
        long start = JobRunner.flashLayout(bench.flash.spec()).region("update").start();
        byte[] stored = bench.store.read(bench.record.digest());
        assertThat(bench.flash.read(start, 16)).containsExactly(Arrays.copyOf(stored, 16));
    }

    @Test
    void aFlashJobThatFailsLeavesTheBoardFaulted() {
        Bench bench = weakBench();
        RunResult result = bench.run(bench.job(JobMode.FLASH_UPDATE), true);
        assertThat(result.passed()).isFalse();
        assertThat(result.faultedBoard()).isTrue();
        assertThat(bench.book.state("bx-14").state()).isEqualTo(BoardStatus.FAULTED);
    }

    @Test
    void aVerifyFailureIsNotRetried() {
        Bench bench = weakBench();
        RunResult result = bench.run(bench.job(JobMode.FLASH_UPDATE), true);
        assertThat(result.willRetry()).isFalse();
        assertThat(bench.faults.forBoard("bx-14").getFirst().kind()).isEqualTo(FaultKind.VERIFY);
    }

    @Test
    void aFailureIsJournalledWithItsCode() {
        Bench bench = deafBench();
        bench.run();
        JournalEntry last = bench.journal.lastEvent("job-00001").orElseThrow();
        assertThat(last.event()).isEqualTo(JournalEvent.FAILED);
        assertThat(last.detail()).containsEntry("code", "programming");
    }

    @Test
    void wearIsCountedWhenAFlashJobRuns() {
        WearLedger ledger = new WearLedger(tmp.resolve("wear.json"));
        Bench bench = new Bench(tmp, "qual", false, false, Optional.of(ledger));
        bench.run(bench.job(JobMode.FLASH_UPDATE), true);
        assertThat(bench.runner.wear()).containsSame(ledger);
        assertThat(ledger.total("bx-14")).isPositive();
    }

    @Test
    void theGatesReadTheStoredImageForItsFlashRead() {
        Bench bench = bench();
        bench.book.add(BoardSetup.kintex("bx-16")
                .flash("m25p128")
                .location("rack-2/shelf-04/slot-4")
                .serial("210308AB9999")
                .outlet(8)
                .build());
        bench.leases.acquire("bx-16", "team-a", "capture", NOW);
        bench.book.moveTo("bx-16", BoardStatus.LEASED, NOW, "", "team-a");
        Bitstream quad = Bitstream.read(Images.image().frames(64).spiRead(0x6B).bitfile());
        ArtifactRecord stored = bench.store.put(quad, "qual");
        ProgramJob job = ProgramJob.builder("job-00002", "bx-16", stored.digest(), JobMode.FLASH_UPDATE, "team-a", NOW)
                .build();
        RunResult result = bench.runner.run(job, NOW, bench.drivers(), Optional.of(true));
        assertThat(result.passed()).isFalse();
        assertThat(result.detail())
                .isEqualTo("the image reads its flash four bits wide and the m25p128 on this board cannot");
        assertThat(bench.book.state("bx-16").state()).isEqualTo(BoardStatus.LEASED);
    }

    @Test
    void aJobForAnImageNobodyStoredIsANotFound() {
        Bench bench = bench();
        ProgramJob unknown = bench.job(JobMode.JTAG, "ab".repeat(32));
        assertThatThrownBy(() -> bench.run(unknown)).isInstanceOf(NotFound.class);
    }

    @Test
    void aJtagJobWithNoTransportFailsRatherThanPretending() {
        Bench bench = bench();
        BoardDrivers flashOnly = new BoardDrivers(Optional.empty(), Optional.of(bench::flashFor));
        RunResult result = bench.runner.run(bench.job(), NOW, flashOnly);
        assertThat(result.passed()).isFalse();
        assertThat(result.willRetry()).isFalse();
    }

    @Test
    void theResultDescribesItself() {
        Bench bench = deafBench();
        String described = bench.run().describe();
        assertThat(described).startsWith("job-00001 queued: ");
        assertThat(described).endsWith("retry in 15s");
    }
}
