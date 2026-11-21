package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.bitstream.Images;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliJobsTest {

    @TempDir
    Path root;

    private BenchDatabase database;
    private Shell shell;

    @BeforeEach
    void workspace() throws IOException {
        String url = Shell.url(root);
        database = new BenchDatabase(Shell.settings(url).toBuilder()
                .artifactRoot(root.resolve("artifacts"))
                .journalRoot(root.resolve("journal"))
                .build());
        database.createSchema();
        database.run(unit -> Inventory.addBoard(
                unit, BoardSetup.kintex("bx-14").build(), Times.now().minus(Duration.ofDays(1))));
        Files.write(root.resolve("radar.bit"), Images.bitfile());
        Files.writeString(root.resolve("timing.rpt"), Images.TIMING, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("usage.rpt"), Images.UTILIZATION, StandardCharsets.UTF_8);
        shell = new Shell(url)
                .env("FPGA_ARTIFACT_ROOT", root.resolve("artifacts").toString())
                .env("FPGA_JOURNAL_ROOT", root.resolve("journal").toString());
    }

    @AfterEach
    void close() {
        database.close();
    }

    private String digest() {
        return shell.run(
                        "--json",
                        "image",
                        "upload",
                        root.resolve("radar.bit").toString(),
                        "--channel",
                        "qual",
                        "--timing",
                        root.resolve("timing.rpt").toString(),
                        "--utilization",
                        root.resolve("usage.rpt").toString())
                .json()
                .get("digest")
                .asText();
    }

    private Shell.Result take() {
        return shell.run("lease", "take", "--board", "bx-14", "--holder", "team-a", "--purpose", "capture");
    }

    private Shell.Result submit(String digest, String mode) {
        return shell.run("job", "submit", "--board", "bx-14", "--image", digest, "--mode", mode, "--by", "team-a");
    }

    private Shell.Result submit(String digest) {
        return submit(digest, "jtag");
    }

    @Test
    void aJobGoesOnTheQueue() {
        Shell.Result result = submit(digest());
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("job-00001 queued");
    }

    @Test
    void aJobForAnImageNobodyHasIsAnError() {
        Shell.Result result = submit("ab".repeat(32));
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("no artifact known");
    }

    @Test
    void jobsAreListedWithTheirState() {
        submit(digest());
        Shell.Result result = shell.run("job", "list");
        assertThat(result.output()).contains("JOB");
        assertThat(result.output()).contains("queued");
    }

    @Test
    void theListingCanBeNarrowedToABoard() {
        submit(digest());
        JsonNode body = shell.run("--json", "job", "list", "--board", "bx-14").json();
        assertThat(Shell.column(body, "job")).containsExactly("job-00001");
    }

    @Test
    void runningWithNothingReadySaysSo() {
        assertThat(shell.run("job", "run").output()).contains("nothing is ready to run");
    }

    @Test
    void aDryRunPassesAgainstTheSimulator() {
        String digest = digest();
        take();
        submit(digest);
        Shell.Result result = shell.run("job", "run");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("passed");
        assertThat(result.output()).contains("done");
    }

    @Test
    void aDryRunLeavesTheBoardLeasedRatherThanProgramming() {
        String digest = digest();
        take();
        submit(digest);
        shell.run("job", "run");
        assertThat(shell.run("--json", "boards", "show", "bx-14")
                        .json()
                        .get("state")
                        .asText())
                .isEqualTo("leased");
    }

    @Test
    void aJobOnABoardNobodyHoldsIsRefusedByTheGates() {
        submit(digest());
        JsonNode body = shell.run("--json", "job", "run").json();
        assertThat(body.get("state").asText()).isEqualTo("failed");
        assertThat(body.get("detail").asText()).contains("nobody holds a lease");
    }

    @Test
    void aFlashJobRunsAgainstTheFakePart() {
        String digest = digest();
        take();
        submit(digest, "flash-update");
        JsonNode body = shell.run("--json", "job", "run").json();
        assertThat(body.get("state").asText()).isEqualTo("passed");
        assertThat(body.get("detail").asText()).contains("verified");
    }

    @Test
    void aGoldenWriteOfAQualifiedImageIsRefused() {
        String digest = digest();
        take();
        submit(digest, "flash-golden");
        JsonNode body = shell.run("--json", "job", "run").json();
        assertThat(body.get("state").asText()).isEqualTo("failed");
        assertThat(body.get("detail").asText()).contains("golden images");
    }

    @Test
    void aRunCanBeAskedForByJobId() {
        String digest = digest();
        take();
        submit(digest);
        Shell.Result result = shell.run("job", "run", "job-00001");
        assertThat(result.output()).contains("job-00001 passed");
    }

    @Test
    void theJournalRecordsTheRun() throws IOException {
        String digest = digest();
        take();
        submit(digest);
        shell.run("job", "run");
        List<String> lines = Files.readAllLines(root.resolve("journal").resolve("jobs.jsonl"), StandardCharsets.UTF_8);
        assertThat(lines).anyMatch(line -> line.contains("\"event\": \"started\""));
        assertThat(lines).anyMatch(line -> line.contains("\"event\": \"passed\""));
    }

    @Test
    void aCancelledJobIsNotOffered() {
        submit(digest());
        Shell.Result result = shell.run("job", "cancel", "job-00001", "--reason", "board went away");
        assertThat(result.output()).contains("job-00001 cancelled");
        assertThat(shell.run("job", "run").output()).contains("nothing is ready to run");
    }

    @Test
    void aSecondJobOnOneBoardWaitsForTheFirst() {
        String digest = digest();
        take();
        submit(digest);
        submit(digest);
        JsonNode body = shell.run("--json", "job", "list", "--state", "queued").json();
        assertThat(body).hasSize(2);
        shell.run("job", "run");
        JsonNode after = shell.run("--json", "job", "list").json();
        assertThat(Shell.column(after, "state")).containsExactly("passed", "queued");
    }
}
