package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.Chains;
import io.github.rkaushikdev.carrierworks.jtag.BitVector;
import io.github.rkaushikdev.carrierworks.jtag.Discover;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Inventory;
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

class CliChainTest {

    @TempDir
    Path tmp;

    private BenchDatabase database;
    private Shell shell;

    @BeforeEach
    void url() {
        String address = Shell.url(tmp);
        database = new BenchDatabase(Shell.settings(address).toBuilder()
                .artifactRoot(tmp.resolve("artifacts"))
                .build());
        database.createSchema();
        database.run(unit -> {
            Inventory.addBoard(
                    unit, BoardSetup.kintex("bx-14").build(), Times.now().minus(Duration.ofDays(1)));
            Inventory.addBoard(
                    unit, BoardSetup.artix("bx-15").build(), Times.now().minus(Duration.ofDays(1)));
        });
        shell = new Shell(address);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private static String healthy(Board board) {
        return Discover.scanBitsFor(Chains.chainFor(board)).toString();
    }

    @Test
    void theExpectedChainListsTheControllerInFrontOfThePart() {
        JsonNode body = shell.run("--json", "chain", "show", "bx-14").json();
        assertThat(Shell.column(body, "label")).containsExactly("sysctl", "u1");
        assertThat(body.get(1).get("device").asText()).isEqualTo("xc7k325t");
    }

    @Test
    void aCarrierWithNoControllerIsOnePosition() {
        JsonNode body = shell.run("--json", "chain", "show", "bx-15").json();
        assertThat(Shell.column(body, "label")).containsExactly("u1");
    }

    @Test
    void aScanAgainstTheSimulatorMatches() {
        Shell.Result result = shell.run("chain", "scan", "bx-14");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("chain matches");
    }

    @Test
    void aMatchingScanReportsWhatAnswered() {
        JsonNode body = shell.run("--json", "chain", "scan", "bx-14").json();
        assertThat(body.get("ok").isBoolean()).isTrue();
        assertThat(body.get("ok").booleanValue()).isTrue();
        assertThat(body.get("found").asText()).contains("xc7k325t");
        assertThat(body.get("moved_to").isNull()).isTrue();
    }

    @Test
    void aScanThatMatchesLeavesTheBoardWhereItWas() {
        shell.run("chain", "scan", "bx-14", "--apply");
        assertThat(shell.run("--json", "boards", "show", "bx-14")
                        .json()
                        .get("state")
                        .asText())
                .isEqualTo("available");
    }

    @Test
    void aCaptureOfAllOnesReadsAsACable() {
        JsonNode body = shell.run("--json", "chain", "diagnose", "bx-14", "1".repeat(96))
                .json();
        assertThat(body.get("ok").isBoolean()).isTrue();
        assertThat(body.get("ok").booleanValue()).isFalse();
        assertThat(body.get("would_move_to").asText()).isEqualTo("faulted");
    }

    @Test
    void theWrongPartOnTheHeaderReadsAsMaintenance() {
        String swapped = Discover.scanBitsFor(
                        Chains.chainFor(BoardSetup.artix("bx-15").build()))
                .toString();
        JsonNode body =
                shell.run("--json", "chain", "diagnose", "bx-14", swapped).json();
        assertThat(body.get("ok").isBoolean()).isTrue();
        assertThat(body.get("ok").booleanValue()).isFalse();
        assertThat(body.get("would_move_to").asText()).isEqualTo("maintenance");
        assertThat(body.get("wiring").isNull()).isTrue();
    }

    @Test
    void aDiagnoseOfAGoodCaptureIsQuiet() {
        String good = healthy(BoardSetup.kintex("bx-14").build());
        JsonNode body = shell.run("--json", "chain", "diagnose", "bx-14", good).json();
        assertThat(body.get("ok").isBoolean()).isTrue();
        assertThat(body.get("ok").booleanValue()).isTrue();
        assertThat(body.get("would_move_to").isNull()).isTrue();
    }

    @Test
    void aCaptureCanComeOutOfAFile() throws IOException {
        Path path = tmp.resolve("capture.txt");
        Files.writeString(path, healthy(BoardSetup.kintex("bx-14").build()) + "\n", StandardCharsets.UTF_8);
        Shell.Result result = shell.run("chain", "diagnose", "bx-14", path.toString(), "--file");
        assertThat(result.output()).contains("chain matches");
    }

    @Test
    void aCaptureThatIsNotBitsIsAnError() {
        Shell.Result result = shell.run("chain", "diagnose", "bx-14", "not bits at all");
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void theUnderlyingCheckAgreesWithTheCommand() {
        Board board = BoardSetup.kintex("bx-14").build();
        assertThat(BitVector.parse(healthy(board))).isEqualTo(Discover.scanBitsFor(Chains.chainFor(board)));
    }
}
