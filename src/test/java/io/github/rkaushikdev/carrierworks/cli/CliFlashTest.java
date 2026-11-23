package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactStore;
import io.github.rkaushikdev.carrierworks.bitstream.Images;
import io.github.rkaushikdev.carrierworks.flash.FlashLayout;
import io.github.rkaushikdev.carrierworks.flash.FlashPlan;
import io.github.rkaushikdev.carrierworks.flash.WearLedger;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliFlashTest {

    @TempDir
    Path root;

    private BenchDatabase database;
    private Shell shell;

    @BeforeEach
    void bench() {
        String url = Shell.url(root);
        database = new BenchDatabase(Shell.settings(url).toBuilder()
                .artifactRoot(root.resolve("artifacts"))
                .wearLedger(root.resolve("wear.json"))
                .build());
        database.createSchema();
        database.run(unit -> {
            Inventory.addBoard(
                    unit, BoardSetup.kintex("bx-14").build(), Times.now().minus(Duration.ofDays(1)));
            Inventory.addBoard(
                    unit, BoardSetup.artix("bx-15").build(), Times.now().minus(Duration.ofDays(1)));
        });
        shell = new Shell(url)
                .env("FPGA_ARTIFACT_ROOT", root.resolve("artifacts").toString())
                .env("FPGA_WEAR_LEDGER", root.resolve("wear.json").toString());
    }

    @AfterEach
    void close() {
        database.close();
    }

    private String stored() throws IOException {
        Files.write(root.resolve("radar.bit"), Images.bitfile());
        return shell.run("--json", "image", "upload", root.resolve("radar.bit").toString())
                .json()
                .get("digest")
                .asText();
    }

    private void wearUp() {
        WearLedger ledger = new WearLedger(root.resolve("wear.json"));
        Map<Long, Long> sectors = new LinkedHashMap<>();
        sectors.put(0L, 90_000L);
        sectors.put(1L, 400L);
        sectors.put(2L, 7L);
        ledger.restore(BoardSetup.kintex("bx-14").build().tag(), sectors);
        ledger.save();
    }

    @Test
    void theLayoutPutsGoldenBelowUpdate() {
        JsonNode body = shell.run("--json", "flash", "layout", "bx-14").json();
        assertThat(Shell.column(body, "region")).containsExactly("golden", "update");
        assertThat(body.get(0).get("start").asText()).isEqualTo("0x00000000");
        assertThat(body.get(1).get("start").asText()).isEqualTo("0x00800000");
    }

    @Test
    void aSmallerPartGivesASmallerLayout() {
        JsonNode body = shell.run("--json", "flash", "layout", "bx-15").json();
        assertThat(body.get(1).get("start").asText()).isEqualTo("0x00400000");
    }

    @Test
    void aBoardNobodyHasIsAnError() {
        Shell.Result result = shell.run("flash", "layout", "bx-99");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("bx-99");
    }

    @Test
    void aWritePlanCostsTheEraseAndTheProgram() throws IOException {
        Shell.Result result = shell.run("flash", "plan", "bx-14", "--image", stored());
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("verify   yes");
        assertThat(result.output()).contains("about");
    }

    @Test
    void aPlanWithoutVerifySaysSo() throws IOException {
        Shell.Result result = shell.run("flash", "plan", "bx-14", "--image", stored(), "--no-verify");
        assertThat(result.output()).contains("verify   no");
    }

    @Test
    void thePlanStepsComeOutAsJson() throws IOException {
        JsonNode body = shell.run("--json", "flash", "plan", "bx-14", "--image", stored())
                .json();
        assertThat(Shell.column(body, "kind")).contains("program");
        assertThat(body.get(0).get("address").asText()).isEqualTo("0x00800000");
    }

    @Test
    void theGoldenRegionStartsAtZero() throws IOException {
        JsonNode body = shell.run("--json", "flash", "plan", "bx-14", "--image", stored(), "--region", "golden")
                .json();
        assertThat(body.get(0).get("address").asText()).isEqualTo("0x00000000");
    }

    @Test
    void anErasePlanNeedsNoImage() {
        Shell.Result result = shell.run("flash", "plan", "bx-14", "--erase", "--region", "update");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("written  0 B");
    }

    @Test
    void aFittedErasePlanUsesSubsectorsAtTheEnds() throws IOException {
        JsonNode sectors = shell.run("--json", "flash", "plan", "bx-14", "--image", stored())
                .json();
        JsonNode fitted = shell.run("--json", "flash", "plan", "bx-14", "--image", stored(), "--fitted")
                .json();
        assertThat(sectors.get(0).get("length").asLong()).isEqualTo(65_536);
        assertThat(fitted.get(0).get("kind").asText()).isEqualTo("erase");
        assertThat(fitted.get(0).get("length").asLong()).isEqualTo(4096);
    }

    @Test
    void subsectorsAndFittedTogetherIsAUsageError() {
        Shell.Result result = shell.run("flash", "plan", "bx-14", "--erase", "--subsectors", "--fitted");
        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).contains("not both");
    }

    @Test
    void aPlanWithNeitherImageNorEraseIsAUsageError() {
        Shell.Result result = shell.run("flash", "plan", "bx-14");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("give an image to write");
    }

    @Test
    void aPlanForAnImageNobodyStoredIsAnError() {
        Shell.Result result = shell.run("flash", "plan", "bx-14", "--image", "cd".repeat(32));
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void wearForTheBenchListsEveryBoard() {
        JsonNode body = shell.run("--json", "flash", "wear").json();
        assertThat(Shell.column(body, "asset")).containsOnly("bx-14", "bx-15");
        assertThat(StreamSupport.stream(body.spliterator(), false))
                .allMatch(entry ->
                        entry.get("erases").isNumber() && entry.get("erases").longValue() == 0);
    }

    @Test
    void aWornSectorShowsUpOnTheBenchListing() {
        wearUp();
        JsonNode body = shell.run("--json", "flash", "wear").json();
        Map<String, String> worn = new LinkedHashMap<>();
        body.forEach(
                entry -> worn.put(entry.get("asset").asText(), entry.get("worn").asText()));
        assertThat(worn).isEqualTo(Map.of("bx-14", "yes", "bx-15", "no"));
    }

    @Test
    void oneBoardListsItsHottestSectorsFirst() {
        wearUp();
        JsonNode body = shell.run("--json", "flash", "wear", "bx-14").json();
        assertThat(StreamSupport.stream(body.spliterator(), false)
                        .map(entry -> entry.get("sector").numberValue()))
                .containsExactly(0, 1, 2);
        assertThat(body.get(0).get("used").asText()).isEqualTo("90.00%");
        assertThat(body.get(0).get("worn").asText()).isEqualTo("yes");
    }

    @Test
    void theListingCanBeCutToTheWorstSector() {
        wearUp();
        JsonNode body =
                shell.run("--json", "flash", "wear", "bx-14", "--top", "1").json();
        assertThat(body).hasSize(1);
    }

    @Test
    void askingForNoSectorsIsAnError() {
        wearUp();
        Shell.Result result = shell.run("flash", "wear", "bx-14", "--top", "0");
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void theCommandCostsTheSamePlanTheRunnerWould() throws IOException {
        String stored = stored();
        byte[] image = new ArtifactStore(root.resolve("artifacts")).read(stored);
        FlashPlan direct = FlashPlan.forWrite(
                FlashLayout.halves(BoardSetup.kintex("bx-14").build().flashSpec()), "update", image);
        JsonNode body =
                shell.run("--json", "flash", "plan", "bx-14", "--image", stored).json();
        assertThat(body).hasSize(direct.steps().size());
    }
}
