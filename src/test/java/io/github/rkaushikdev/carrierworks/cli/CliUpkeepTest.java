package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardSlot;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.services.Upkeep;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliUpkeepTest {

    @TempDir
    Path tmp;

    private BenchDatabase database;
    private Shell shell;

    /** A board with nothing fitted that measures anything. */
    private static Board plain(String asset) {
        return BoardSetup.artix(asset)
                .slots(new BoardSlot("pmod0", "pmod", "pmod-rs485"), new BoardSlot("fmc0", "fmc-lpc"))
                .build();
    }

    @BeforeEach
    void database() {
        String url = Shell.url(tmp);
        database = Shell.migrated(url);
        database.run(unit -> {
            Inventory.addBoard(
                    unit, BoardSetup.kintex("bx-14").build(), Times.now().minus(Duration.ofDays(400)));
            Inventory.addBoard(unit, plain("bx-15"), Times.now().minus(Duration.ofDays(400)));
        });
        shell = new Shell(url);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private void calibrate(int daysAgo) {
        database.run(unit ->
                Upkeep.logVisit(unit, "bx-14", "calibration", Times.now().minus(Duration.ofDays(daysAgo)), "sam"));
    }

    private static void assertFlag(JsonNode body, String field, boolean expected) {
        assertThat(body.get(field).isBoolean()).as(field).isTrue();
        assertThat(body.get(field).booleanValue()).as(field).isEqualTo(expected);
    }

    @Test
    void aVisitIsLogged() {
        Shell.Result result = shell.run("upkeep", "log", "bx-14", "--by", "sam", "--notes", "reseated the fmc");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("reseated the fmc");
    }

    @Test
    void aKindTheBenchDoesNotKnowIsRefused() {
        Shell.Result result = shell.run("upkeep", "log", "bx-14", "--by", "sam", "--kind", "percussive");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("no such maintenance kind");
    }

    @Test
    void aVisitToABoardNobodyHasIsRefused() {
        assertThat(shell.run("upkeep", "log", "bx-99", "--by", "sam").exitCode())
                .isNotZero();
    }

    @Test
    void loggingAloneLeavesTheBoardWhereItWas() {
        shell.run("boards", "state", "bx-14", "maintenance", "--reason", "fan");
        shell.run("upkeep", "log", "bx-14", "--by", "sam");
        assertThat(shell.run("--json", "boards", "show", "bx-14")
                        .json()
                        .get("state")
                        .asText())
                .isEqualTo("maintenance");
    }

    @Test
    void donePutsTheBoardBack() {
        shell.run("boards", "state", "bx-14", "maintenance", "--reason", "fan");
        Shell.Result result = shell.run("upkeep", "done", "bx-14", "--by", "sam", "--notes", "new fan");
        assertThat(result.output()).contains("bx-14 is available");
        assertThat(shell.run("--json", "boards", "show", "bx-14")
                        .json()
                        .get("state")
                        .asText())
                .isEqualTo("available");
    }

    @Test
    void doneRecordsTheFaultsItCleared() {
        shell.run("boards", "state", "bx-14", "faulted", "--reason", "chain went away");
        shell.run("upkeep", "done", "bx-14", "--by", "sam", "--cleared", "2");
        JsonNode body = shell.run("--json", "upkeep", "history", "bx-14").json();
        assertThat(body.get(0).get("cleared").numberValue()).isEqualTo(2);
    }

    @Test
    void theHistoryOfOneBoardIsListed() {
        shell.run("upkeep", "log", "bx-14", "--by", "sam", "--notes", "first");
        shell.run("upkeep", "log", "bx-14", "--by", "kim", "--notes", "second");
        JsonNode body = shell.run("--json", "upkeep", "history", "bx-14").json();
        assertThat(Shell.column(body, "notes")).containsExactly("first", "second");
    }

    @Test
    void theWholeBenchHistoryCoversEveryBoard() {
        shell.run("upkeep", "log", "bx-14", "--by", "sam");
        shell.run("upkeep", "log", "bx-15", "--by", "kim");
        JsonNode body = shell.run("--json", "upkeep", "history").json();
        assertThat(Shell.column(body, "asset")).containsOnly("bx-14", "bx-15");
    }

    @Test
    void historyForABoardNobodyHasIsAnError() {
        assertThat(shell.run("upkeep", "history", "bx-99").exitCode()).isNotZero();
    }

    @Test
    void aBoardThatWasNeverCalibratedSaysSo() {
        JsonNode body = shell.run("--json", "upkeep", "calibration", "bx-14").json();
        assertFlag(body, "required", true);
        assertFlag(body, "never", true);
        assertFlag(body, "valid", false);
    }

    @Test
    void aBoardWithNothingMeasuringNeedsNone() {
        JsonNode body = shell.run("--json", "upkeep", "calibration", "bx-15").json();
        assertFlag(body, "required", false);
        assertFlag(body, "valid", true);
    }

    @Test
    void aFreshCalibrationHolds() {
        calibrate(5);
        JsonNode body = shell.run("--json", "upkeep", "calibration", "bx-14").json();
        assertFlag(body, "valid", true);
    }

    @Test
    void anOldCalibrationHasRunOut() {
        calibrate(400);
        JsonNode body = shell.run("--json", "upkeep", "calibration", "bx-14").json();
        assertFlag(body, "valid", false);
    }

    @Test
    void theDueListNamesWhatHasRunOut() {
        JsonNode body = shell.run("--json", "upkeep", "due").json();
        assertThat(Shell.column(body, "asset")).containsExactly("bx-14");
        assertThat(body.get(0).get("last").asText()).isEqualTo("never");
    }

    @Test
    void aCalibratedBoardLeavesTheDueList() {
        calibrate(5);
        JsonNode body = shell.run("--json", "upkeep", "due").json();
        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    @Test
    void aCalibrationVisitCanBeTheThingThatReturnsTheBoard() {
        shell.run("boards", "state", "bx-14", "maintenance", "--reason", "due");
        shell.run("upkeep", "done", "bx-14", "--by", "sam", "--kind", "calibration");
        assertFlag(shell.run("--json", "upkeep", "calibration", "bx-14").json(), "valid", true);
    }
}
