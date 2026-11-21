package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.bitstream.Images;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.util.Json;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliBundleTest {

    @TempDir
    Path root;

    private BenchDatabase database;
    private Shell shell;

    @BeforeEach
    void workspace() throws IOException {
        String url = Shell.url(root);
        database = new BenchDatabase(Shell.settings(url).toBuilder()
                .artifactRoot(root.resolve("artifacts"))
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

    private Shell.Result make(String digest, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--json",
                "image",
                "bundle",
                digest,
                "--into",
                root.resolve("out").toString()));
        args.addAll(List.of(extra));
        return shell.run(args.toArray(String[]::new));
    }

    private Path bundle(String digest) {
        return root.resolve("out").resolve(digest.substring(0, 12));
    }

    @Test
    void aBundleIsWrittenWhereItWasAskedFor() {
        String digest = digest();
        JsonNode body = make(digest).json();
        assertThat(body.get("root").asText()).isEqualTo(bundle(digest).toString());
        assertThat(Shell.texts(body.get("files"))).contains("manifest.json");
    }

    @Test
    void theBundleCarriesTheReportsThatWereRead() {
        JsonNode body = make(digest()).json();
        assertThat(body.get("reports").numberValue()).isEqualTo(1);
        assertThat(Shell.texts(body.get("files"))).contains("reports.json");
    }

    @Test
    void theBundleRecordsWhoSignedItOff() throws IOException {
        String digest = digest();
        JsonNode body = make(digest, "--approved-by", "kim").json();
        Object manifest = Json.parse(Files.readString(bundle(digest).resolve("manifest.json"), StandardCharsets.UTF_8));
        assertThat(manifest).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) manifest).get("approvals")).isEqualTo(List.of("kim"));
        assertThat(body.get("manifest_digest").asText()).isNotEmpty();
    }

    @Test
    void theLoadsOfTheImageAreInTheBundle() {
        String digest = digest();
        shell.run("lease", "take", "--board", "bx-14", "--holder", "team-a", "--purpose", "capture");
        shell.run("job", "submit", "--board", "bx-14", "--image", digest, "--by", "team-a");
        JsonNode body = make(digest).json();
        assertThat(body.get("jobs").numberValue()).isEqualTo(1);
    }

    @Test
    void bundlingAnImageNobodyHasIsAnError() {
        Shell.Result result = make("ab".repeat(32));
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void bundlingTwiceIntoTheSamePlaceIsRefused() {
        String digest = digest();
        make(digest);
        assertThat(make(digest).exitCode()).isNotZero();
    }

    @Test
    void aFreshBundleChecksOut() {
        String digest = digest();
        make(digest);
        Shell.Result result = shell.run("image", "check-bundle", bundle(digest).toString());
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("matches its manifest");
    }

    @Test
    void anEditedBundleIsCaught() throws IOException {
        String digest = digest();
        make(digest);
        Files.write(bundle(digest).resolve("image.bin"), "not the image".getBytes(StandardCharsets.US_ASCII));
        Shell.Result result = shell.run("image", "check-bundle", bundle(digest).toString());
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("image.bin does not match");
    }

    @Test
    void checkingSomethingThatIsNotABundleIsAnError() {
        assertThat(shell.run("image", "check-bundle", root.toString()).exitCode())
                .isNotZero();
    }
}
