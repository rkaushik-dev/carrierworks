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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliBuildTest {

    private static final String REVISION = "9f2c11a6b3d4e5f60718293a4b5c6d7e8f901234";

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

    private Shell.Result recordAs(String digest, String revision, String buildId, String... extra) {
        List<String> args = new ArrayList<>(
                List.of("--json", "image", "build", digest, "--revision", revision, "--build-id", buildId));
        args.addAll(List.of(extra));
        return shell.run(args.toArray(String[]::new));
    }

    private Shell.Result record(String digest, String... extra) {
        return recordAs(digest, REVISION, "nightly-41", extra);
    }

    /** One clean load of the image onto the board. */
    private void load(String digest) {
        shell.run("lease", "take", "--board", "bx-14", "--holder", "team-a", "--purpose", "capture");
        shell.run("job", "submit", "--board", "bx-14", "--image", digest, "--by", "team-a");
        shell.run("job", "run");
    }

    @Test
    void aBuildIsWrittenDown() {
        JsonNode body = record(digest()).json();
        assertThat(body.get("revision").asText()).isEqualTo(REVISION);
        assertThat(body.get("build_id").asText()).isEqualTo("nightly-41");
        assertThat(body.get("builds").numberValue()).isEqualTo(1);
    }

    @Test
    void theLineSaysWhereItCameFrom() {
        Shell.Result result = shell.run("image", "build", digest(), "--revision", REVISION, "--build-id", "nightly-41");
        assertThat(result.output()).contains("built from 9f2c11a");
    }

    @Test
    void aSecondBuildOfTheSameBytesIsKeptToo() {
        String digest = digest();
        recordAs(digest, REVISION, "nightly-41");
        JsonNode body = recordAs(digest, REVISION, "nightly-42").json();
        assertThat(body.get("builds").numberValue()).isEqualTo(2);
    }

    @Test
    void aDirtyTreeIsRecorded() {
        JsonNode body = record(digest(), "--dirty").json();
        assertThat(body.get("dirty").isBoolean()).isTrue();
        assertThat(body.get("dirty").booleanValue()).isTrue();
    }

    @Test
    void theBranchAndBuilderAreKept() {
        JsonNode body =
                record(digest(), "--branch", "main", "--builder", "build-07").json();
        assertThat(body.get("branch").asText()).isEqualTo("main");
    }

    @Test
    void aRevisionThatIsNotAGitNameIsRefused() {
        Shell.Result result = recordAs(digest(), "yesterday", "nightly-41");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("source revision");
    }

    @Test
    void aBuildForAnImageNobodyHasIsRefused() {
        assertThat(record("ab".repeat(32)).exitCode()).isNotZero();
    }

    @Test
    void theBuildShowsUpOnTheImage() {
        String digest = digest();
        record(digest);
        JsonNode body = shell.run("--json", "image", "show", digest).json();
        assertThat(Shell.texts(body.get("builds"))).containsExactly("nightly-41");
    }

    @Test
    void anImageWithNoBuildCannotGoGolden() {
        String digest = digest();
        load(digest);
        Shell.Result result = shell.run("image", "promote", digest, "golden", "--approve", "kim");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("nothing recorded where this image came from");
    }

    @Test
    void aBuildAndThreeCleanLoadsGetItThere() {
        String digest = digest();
        record(digest, "--constraints", "c0ffee", "--tool", "2024.1");
        for (int round = 0; round < 3; round++) {
            load(digest);
        }
        JsonNode body = shell.run("--json", "image", "promote", digest, "golden", "--approve", "kim")
                .json();
        assertThat(body.get("channel").asText()).isEqualTo("golden");
    }

    @Test
    void twoBuildsDisagreeingAboutTheSourceHoldItBack() {
        String digest = digest();
        recordAs(digest, REVISION, "nightly-41", "--constraints", "c0ffee");
        recordAs(digest, "0".repeat(40), "nightly-42", "--constraints", "c0ffee");
        for (int round = 0; round < 3; round++) {
            load(digest);
        }
        Shell.Result result = shell.run("image", "promote", digest, "golden", "--approve", "kim");
        assertThat(result.exitCode()).isNotZero();
    }
}
