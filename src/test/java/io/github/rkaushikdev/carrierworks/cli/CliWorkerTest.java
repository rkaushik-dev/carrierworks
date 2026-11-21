package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactStore;
import io.github.rkaushikdev.carrierworks.bitstream.Bitstream;
import io.github.rkaushikdev.carrierworks.bitstream.Images;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.jobs.JobMode;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.services.Leasing;
import io.github.rkaushikdev.carrierworks.services.Programming;
import io.github.rkaushikdev.carrierworks.services.Registry;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliWorkerTest {

    @TempDir
    Path tmp;

    private BenchDatabase database;
    private String digest;
    private Shell shell;

    @BeforeEach
    void workspace() {
        String url = Shell.url(tmp);
        database = new BenchDatabase(Shell.settings(url).toBuilder()
                .artifactRoot(tmp.resolve("artifacts"))
                .journalRoot(tmp.resolve("journal"))
                .build());
        database.createSchema();
        ArtifactStore store = new ArtifactStore(tmp.resolve("artifacts"));
        digest = database.apply(unit -> {
            Inventory.addBoard(
                    unit, BoardSetup.kintex("bx-14").build(), Times.now().minus(Duration.ofDays(1)));
            Inventory.addBoard(
                    unit, BoardSetup.artix("bx-15").build(), Times.now().minus(Duration.ofDays(1)));
            return Registry.ingest(unit, store, Bitstream.read(Images.bitfile()), Times.now(), "qual")
                    .digest();
        });
        shell = new Shell(url)
                .env("FPGA_ARTIFACT_ROOT", tmp.resolve("artifacts").toString())
                .env("FPGA_JOURNAL_ROOT", tmp.resolve("journal").toString());
    }

    @AfterEach
    void close() {
        database.close();
    }

    private ProgramJob queueJob(String board, JobMode mode, boolean hold) {
        return database.apply(unit -> {
            if (hold) {
                Leasing.acquire(unit, board, "team-a", "capture", Times.now().minus(Duration.ofMinutes(1)));
            }
            return Programming.submit(unit, board, digest, mode, "team-a", Times.now());
        });
    }

    private ProgramJob queueJob() {
        return queueJob("bx-14", JobMode.JTAG, true);
    }

    /** A job the database calls running, as a crash would have left it. */
    private ProgramJob strand(JobMode mode) {
        ProgramJob job = queueJob("bx-14", mode, true);
        database.run(unit -> {
            unit.jobs().save(unit.jobs().get(job.id()).start(Times.now()));
            Inventory.moveState(unit, "bx-14", BoardStatus.PROGRAMMING, Times.now(), "", "team-a");
        });
        return job;
    }

    private ProgramJob strand() {
        return strand(JobMode.JTAG);
    }

    private Shell.Result request() {
        return shell.run("queue", "submit", "--holder", "team-b", "--purpose", "capture", "--needs", "device=xc7a100t");
    }

    /** Whether a listing holds a row of exactly these two fields. */
    private static boolean hasRow(JsonNode rows, String what, String subject) {
        for (JsonNode entry : rows) {
            if (entry.size() == 2
                    && entry.path("what").asText().equals(what)
                    && entry.path("subject").asText().equals(subject)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aQuietBenchHasNothingToDo() {
        assertThat(shell.run("worker", "run").output()).contains("nothing to do");
    }

    @Test
    void theWorkerRunsAReadyJob() {
        ProgramJob job = queueJob();
        Shell.Result result = shell.run("worker", "run");
        assertThat(result.output()).contains("ran       " + job.id() + " passed");
    }

    @Test
    void theWorkerHandsOutABoardBeforeRunningJobs() {
        request();
        JsonNode body = shell.run("--json", "worker", "run").json();
        assertThat(Shell.texts(body.get("assigned"))).containsExactly("req-0001 -> bx-15");
    }

    @Test
    void dispatchCanBeLeftOut() {
        request();
        JsonNode body = shell.run("--json", "worker", "run", "--no-dispatch").json();
        assertThat(body.get("assigned").isArray()).isTrue();
        assertThat(body.get("assigned")).isEmpty();
    }

    @Test
    void theWorkerRecoversAnInterruptedLoadFirst() {
        ProgramJob job = strand();
        JsonNode body = shell.run("--json", "worker", "run").json();
        assertThat(body.get("recovered").get(0).asText()).contains("requeued " + job.id());
    }

    @Test
    void anInterruptedFlashWriteFaultsTheBoard() {
        strand(JobMode.FLASH_UPDATE);
        shell.run("worker", "run");
        assertThat(shell.run("--json", "boards", "show", "bx-14")
                        .json()
                        .get("state")
                        .asText())
                .isEqualTo("faulted");
    }

    @Test
    void recoveryCanBeSkipped() {
        strand();
        JsonNode body = shell.run("--json", "worker", "run", "--no-recover").json();
        assertThat(body.get("recovered").isArray()).isTrue();
        assertThat(body.get("recovered")).isEmpty();
    }

    @Test
    void theRecoverCommandSaysWhenNothingWasInterrupted() {
        assertThat(shell.run("worker", "recover").output()).contains("nothing was interrupted");
    }

    @Test
    void theRecoverCommandListsWhatItDid() {
        ProgramJob job = strand();
        JsonNode body = shell.run("--json", "worker", "recover").json();
        assertThat(hasRow(body, "requeued", job.id())).as(body.toString()).isTrue();
        assertThat(hasRow(body, "returned", "bx-14")).as(body.toString()).isTrue();
    }

    @Test
    void twoRoundsRunTwoJobs() {
        queueJob();
        queueJob("bx-14", JobMode.JTAG, false);
        JsonNode body = shell.run("--json", "worker", "run", "--rounds", "2").json();
        assertThat(Shell.column(body.get("ran"), "state")).containsExactly("passed", "passed");
    }

    @Test
    void aRoundWithNothingReadyStopsEarly() {
        queueJob();
        JsonNode body = shell.run("--json", "worker", "run", "--rounds", "5").json();
        assertThat(body.get("ran")).hasSize(1);
    }

    @Test
    void zeroRoundsIsAUsageError() {
        Shell.Result result = shell.run("worker", "run", "--rounds", "0");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("at least one round");
    }

    @Test
    void theJournalRecordsWhatTheWorkerRan() throws IOException {
        queueJob();
        shell.run("worker", "run");
        String lines = Files.readString(tmp.resolve("journal").resolve("jobs.jsonl"), StandardCharsets.UTF_8);
        assertThat(lines).contains("\"event\": \"passed\"");
    }

    @Test
    void aJobThatFailsItsGatesIsReportedNotHidden() {
        queueJob("bx-14", JobMode.JTAG, false);
        JsonNode body = shell.run("--json", "worker", "run").json();
        assertThat(body.get("ran").get(0).get("state").asText()).isEqualTo("failed");
        assertThat(body.get("ran").get(0).get("detail").asText()).contains("lease");
    }
}
