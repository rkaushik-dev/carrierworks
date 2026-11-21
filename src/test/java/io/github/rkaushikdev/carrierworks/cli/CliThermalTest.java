package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.services.Thermal;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliThermalTest {

    private static final Duration EVERY = Duration.ofSeconds(20);

    @TempDir
    Path tmp;

    private BenchDatabase database;
    private Shell shell;

    @BeforeEach
    void database() {
        String url = Shell.url(tmp);
        database = Shell.migrated(url);
        database.run(unit -> {
            Inventory.addBoard(
                    unit, BoardSetup.kintex("bx-14").build(), Times.now().minus(Duration.ofDays(1)));
            Inventory.addBoard(
                    unit, BoardSetup.artix("bx-15").build(), Times.now().minus(Duration.ofDays(1)));
        });
        shell = new Shell(url);
    }

    @AfterEach
    void close() {
        database.close();
    }

    /** Readings ending a moment ago, so the window they land in is the live one. */
    private void feed(String board, int... readings) {
        Instant last = Times.now().minus(Duration.ofSeconds(5));
        database.run(unit -> {
            for (int index = 0; index < readings.length; index++) {
                int value = readings[readings.length - 1 - index];
                Thermal.record(unit, board, last.minus(EVERY.multipliedBy(index)), value);
            }
        });
    }

    private static Map<String, String> byAsset(JsonNode body, String field) {
        Map<String, String> found = new LinkedHashMap<>();
        body.forEach(
                entry -> found.put(entry.get("asset").asText(), entry.get(field).asText()));
        return found;
    }

    @Test
    void aReadingGoesInFromTheCommandLine() {
        Shell.Result result = shell.run("thermal", "record", "bx-14", "64.5C");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("64.5C");
    }

    @Test
    void aReadingCanBeGivenInPlainDegrees() {
        JsonNode body = shell.run("--json", "thermal", "record", "bx-14", "71").json();
        assertThat(body.get("millidegrees").numberValue()).isEqualTo(71_000);
    }

    @Test
    void aReadingOffAnotherSensorKeepsItsSource() {
        JsonNode body = shell.run("--json", "thermal", "record", "bx-14", "24C", "--source", "inlet")
                .json();
        assertThat(body.get("source").asText()).isEqualTo("inlet");
    }

    @Test
    void aReadingForABoardNobodyHasIsAnError() {
        Shell.Result result = shell.run("thermal", "record", "bx-99", "64C");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("bx-99");
    }

    @Test
    void aReadingThatIsNotATemperatureIsAnError() {
        Shell.Result result = shell.run("thermal", "record", "bx-14", "warm");
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void aBenchWithNoReadingsIsAllUnknown() {
        JsonNode body = shell.run("--json", "thermal", "status").json();
        assertThat(Shell.column(body, "state")).containsOnly("unknown");
    }

    @Test
    void aCoolBoardReadsAsNormal() {
        feed("bx-14", 60_000, 61_000, 62_000);
        JsonNode body = shell.run("--json", "thermal", "status").json();
        assertThat(byAsset(body, "state").get("bx-14")).isEqualTo("normal");
    }

    @Test
    void theWorstBoardIsListedFirst() {
        feed("bx-14", 60_000, 61_000);
        feed("bx-15", 88_000, 89_000);
        JsonNode body = shell.run("--json", "thermal", "status").json();
        assertThat(body.get(0).get("asset").asText()).isEqualTo("bx-15");
    }

    @Test
    void theListingCanBeCutToWhatIsAlarming() {
        feed("bx-14", 60_000, 61_000);
        feed("bx-15", 97_000, 98_000);
        JsonNode body = shell.run("--json", "thermal", "status", "--alarming").json();
        assertThat(Shell.column(body, "asset")).containsExactly("bx-15");
    }

    @Test
    void statusReportsTheWindowAverageRatherThanOneSpike() {
        feed("bx-14", 60_000, 66_500);
        JsonNode body = shell.run("--json", "thermal", "status").json();
        assertThat(byAsset(body, "reading").get("bx-14")).isEqualTo("63.2C");
    }

    @Test
    void historyComesBackOldestFirst() {
        feed("bx-14", 60_000, 61_000, 62_000);
        JsonNode body = shell.run("--json", "thermal", "history", "bx-14").json();
        assertThat(Shell.column(body, "reading")).containsExactly("60.0C", "61.0C", "62.0C");
    }

    @Test
    void historyForABoardNobodyHasIsAnError() {
        assertThat(shell.run("thermal", "history", "bx-99").exitCode()).isNotZero();
    }

    @Test
    void aQuietBenchHasNothingForTheSupervisorToDo() {
        feed("bx-14", 60_000, 61_000);
        feed("bx-15", 55_000, 56_000);
        assertThat(shell.run("thermal", "tick").output()).contains("nothing to do");
    }

    @Test
    void aHotBoardIsShutDownByTheTick() {
        feed("bx-14", 101_000, 101_500);
        JsonNode body = shell.run("--json", "thermal", "tick").json();
        assertThat(body.get(0).get("asset").asText()).isEqualTo("bx-14");
        assertThat(body.get(0).get("action").asText()).isEqualTo("shutdown");
    }

    @Test
    void theShutdownTakesTheBoardOutOfService() {
        feed("bx-14", 101_000, 101_500);
        shell.run("thermal", "tick");
        assertThat(shell.run("--json", "boards", "show", "bx-14")
                        .json()
                        .get("state")
                        .asText())
                .isEqualTo("faulted");
    }

    @Test
    void aWarmBoardIsOnlyNoted() {
        feed("bx-14", 87_000, 87_500);
        feed("bx-15", 55_000, 56_000);
        shell.run("thermal", "tick");
        assertThat(shell.run("--json", "boards", "show", "bx-14")
                        .json()
                        .get("state")
                        .asText())
                .isEqualTo("available");
    }

    @Test
    void pruningSaysHowMuchItDropped() {
        database.run(unit -> Thermal.record(unit, "bx-14", Times.now().minus(Duration.ofDays(30)), 60_000));
        JsonNode body = shell.run("--json", "thermal", "prune").json();
        assertThat(body.get("dropped").numberValue()).isEqualTo(1);
    }

    @Test
    void pruningLeavesTheLiveWindowAlone() {
        feed("bx-14", 60_000, 61_000);
        JsonNode body = shell.run("--json", "thermal", "prune").json();
        assertThat(body.get("dropped").numberValue()).isEqualTo(0);
        assertThat(shell.run("--json", "thermal", "history", "bx-14").json()).hasSize(2);
    }
}
