package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.bitstream.Images;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliImagesTest {

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
        Files.write(root.resolve("radar.bit"), Images.bitfile());
        Files.write(root.resolve("other.bit"), Images.bitfile(20));
        Files.writeString(root.resolve("timing.rpt"), Images.TIMING, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("failing.rpt"), Images.FAILING_TIMING, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("usage.rpt"), Images.UTILIZATION, StandardCharsets.UTF_8);
        shell = new Shell(url)
                .env("FPGA_ARTIFACT_ROOT", root.resolve("artifacts").toString());
    }

    @AfterEach
    void close() {
        database.close();
    }

    private String file(String name) {
        return root.resolve(name).toString();
    }

    private String uploadWithReports(String bitFile, String timing) {
        return shell.run(
                        "--json",
                        "image",
                        "upload",
                        file(bitFile),
                        "--timing",
                        file(timing),
                        "--utilization",
                        file("usage.rpt"))
                .json()
                .get("digest")
                .asText();
    }

    @Test
    void anImageIsStoredFromAFile() {
        Shell.Result result = shell.run("image", "upload", file("radar.bit"));
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("radar_frontend");
        assertThat(result.output()).contains("stored in dev");
    }

    @Test
    void theReportsCanComeUpWithIt() {
        Shell.Result result = shell.run(
                "image",
                "upload",
                file("radar.bit"),
                "--timing",
                file("timing.rpt"),
                "--utilization",
                file("usage.rpt"));
        assertThat(result.output()).contains("timing closed");
    }

    @Test
    void aBuildThatMissedTimingSaysSo() {
        Shell.Result result = shell.run(
                "image",
                "upload",
                file("radar.bit"),
                "--timing",
                file("failing.rpt"),
                "--utilization",
                file("usage.rpt"));
        assertThat(result.output()).contains("did not close");
    }

    @Test
    void aFileThatIsNotABitFileIsAnError() throws IOException {
        Files.write(root.resolve("junk.bit"), "not a bitstream".getBytes(StandardCharsets.US_ASCII));
        Shell.Result result = shell.run("image", "upload", file("junk.bit"));
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("bit file");
    }

    @Test
    void imagesAreListed() {
        shell.run("image", "upload", file("radar.bit"));
        Shell.Result result = shell.run("image", "list");
        assertThat(result.output()).contains("DIGEST");
        assertThat(result.output()).contains("radar_frontend");
    }

    @Test
    void theListingCanBeNarrowedByChannel() {
        shell.run("image", "upload", file("radar.bit"));
        shell.run("image", "upload", file("other.bit"), "--channel", "qual");
        JsonNode body =
                shell.run("--json", "image", "list", "--channel", "qual").json();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("channel").asText()).isEqualTo("qual");
    }

    @Test
    void showingAnImageSaysWhatIsKnown() {
        String digest = shell.run("--json", "image", "upload", file("radar.bit"))
                .json()
                .get("digest")
                .asText();
        Shell.Result result = shell.run("image", "show", digest);
        assertThat(result.output()).contains("radar_frontend");
        assertThat(result.output()).contains("timing   not read");
        assertThat(result.output()).contains("loads    0 passed, 0 failed");
        assertThat(result.output()).contains("options  defaults");
        assertThat(result.output()).contains("crc      crc not written");
    }

    @Test
    void showingAnImageSaysWhatItSetsOnTheDevice() throws IOException {
        Files.write(
                root.resolve("quad.bit"),
                Images.image().spiRead(0x6C).watchdog(0x2000).frames(24).bitfile());
        String digest = shell.run("--json", "image", "upload", file("quad.bit"))
                .json()
                .get("digest")
                .asText();
        Shell.Result result = shell.run("image", "show", digest);
        assertThat(result.output()).contains("options  x4 read 0x6c, 4 address bytes, watchdog 0x00002000");
        JsonNode body = shell.run("--json", "image", "show", digest).json();
        assertThat(body.get("options").asText()).isEqualTo("x4 read 0x6c, 4 address bytes, watchdog 0x00002000");
        assertThat(body.get("crc").asText()).isEqualTo("crc not written");
    }

    @Test
    void anImageThatFailsItsCrcIsNotStored() throws IOException {
        Files.write(
                root.resolve("bad.bit"), Images.image().write(0, 0x1234_5678L).bitfile());
        Shell.Result result = shell.run("image", "upload", file("bad.bit"));
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("fails its own crc check");
        assertThat(shell.run("--json", "image", "list").json()).isEmpty();
    }

    @Test
    void showingAnImageAsJsonCarriesTheSlack() {
        String digest = uploadWithReports("radar.bit", "timing.rpt");
        JsonNode body = shell.run("--json", "image", "show", digest).json();
        assertThat(body.get("timing_closed").isBoolean()).isTrue();
        assertThat(body.get("timing_closed").booleanValue()).isTrue();
        assertThat(body.get("worst_slack_ps").numberValue()).isEqualTo(137);
    }

    @Test
    void anImageNobodyStoredIsAnError() {
        Shell.Result result = shell.run("image", "show", "ab".repeat(32));
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("no artifact known");
    }

    @Test
    void promotionRefusesWithoutTheEvidence() {
        String digest = shell.run("--json", "image", "upload", file("radar.bit"))
                .json()
                .get("digest")
                .asText();
        Shell.Result result = shell.run("image", "promote", digest, "qual");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("timing");
    }

    @Test
    void theBaselineIsShownAndPinned() {
        String first = uploadWithReports("radar.bit", "timing.rpt");
        Shell.Result shown = shell.run("image", "baseline", "radar_frontend");
        assertThat(shown.output()).contains(first.substring(0, 12));
        String second = uploadWithReports("other.bit", "timing.rpt");
        Shell.Result pinned = shell.run("image", "baseline", "radar_frontend", "--pin", second);
        assertThat(pinned.output()).contains(second.substring(0, 12));
    }

    @Test
    void aDesignWithNoBaselineIsAnError() {
        Shell.Result result = shell.run("image", "baseline", "beamformer");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("no baseline known as beamformer");
    }

    @Test
    void regressionsReadCleanAgainstTheBaseline() {
        String digest = uploadWithReports("radar.bit", "timing.rpt");
        Shell.Result result = shell.run("image", "regressions", digest);
        assertThat(result.output()).contains("nothing worse than the baseline");
    }
}
