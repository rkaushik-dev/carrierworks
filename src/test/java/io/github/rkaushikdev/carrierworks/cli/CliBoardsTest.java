package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.services.Leasing;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliBoardsTest {

    private static final Instant WHEN = Instant.parse("2026-02-13T09:00:00Z");

    @TempDir
    Path tmp;

    private String url;
    private BenchDatabase database;
    private Shell shell;

    @BeforeEach
    void bench() {
        url = Shell.url(tmp);
        database = Shell.migrated(url);
        database.run(unit -> {
            Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), WHEN);
            Inventory.addBoard(unit, BoardSetup.artix("bx-15").build(), WHEN);
        });
        shell = new Shell(url);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void theVersionCommandSaysWhichSite() {
        Shell.Result result = shell.run("version");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("fpga bench");
        assertThat(result.output()).contains("lab-a");
    }

    @Test
    void boardsListPrintsColumns() {
        Shell.Result result = shell.run("boards", "list");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("ASSET");
        assertThat(result.output()).contains("bx-14");
        assertThat(result.output()).contains("xc7k325t");
    }

    @Test
    void boardsListPrintsJsonWhenAsked() {
        JsonNode body = shell.run("--json", "boards", "list").json();
        assertThat(Shell.column(body, "asset")).containsOnly("bx-14", "bx-15");
        assertThat(body.get(0).get("state").asText()).isEqualTo("available");
    }

    @Test
    void anEmptyBenchSaysSo() {
        String empty = Shell.url(tmp, "empty");
        Shell.migrated(empty).close();
        Shell.Result result = Shell.typeBare(Map.of(), "", "--database", empty, "boards", "list");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("nothing to show");
    }

    @Test
    void theRackFilterNarrowsTheListing() {
        Shell.Result result = shell.run("--json", "boards", "list", "--rack", "rack-4");
        assertThat(Shell.column(result.json(), "asset")).containsExactly("bx-15");
    }

    @Test
    void theAvailableFilterLeavesOutTheBusyOnes() {
        shell.run("boards", "state", "bx-14", "leased");
        Shell.Result result = shell.run("--json", "boards", "list", "--available");
        assertThat(Shell.column(result.json(), "asset")).containsExactly("bx-15");
    }

    @Test
    void showingABoardPrintsWhatIsOnIt() {
        Shell.Result result = shell.run("boards", "show", "bx-14");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("rx-carrier-b");
        assertThat(result.output()).contains("held by  nobody");
        assertThat(result.output()).contains("jtag-hs2");
    }

    @Test
    void showingABoardAsJsonCarriesTheDetail() {
        JsonNode body = shell.run("--json", "boards", "show", "bx-14").json();
        assertThat(body.get("device").asText()).isEqualTo("xc7k325t");
        assertThat(Shell.texts(body.get("cards"))).containsExactly("fmc-adc-4ch-14b");
        assertThat(body.get("transceivers_free").numberValue()).isEqualTo(12);
        assertThat(body.get("held_by").isNull()).isTrue();
    }

    @Test
    void aBoardNobodyHasIsAnErrorWithAReadableLine() {
        Shell.Result result = shell.run("boards", "show", "bx-99");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("no board known as bx-99");
    }

    @Test
    void aStateMovePrintsWhereItLanded() {
        Shell.Result result = shell.run("boards", "state", "bx-14", "leased");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("bx-14 is now leased");
        assertThat(shell.run("--json", "boards", "show", "bx-14")
                        .json()
                        .get("state")
                        .asText())
                .isEqualTo("leased");
    }

    @Test
    void aMoveTheStateMachineRefusesIsAnError() {
        Shell.Result result = shell.run("boards", "state", "bx-14", "quarantined", "--reason", "no");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("cannot go from available to quarantined");
    }

    @Test
    void faultingABoardRecordsTheReason() {
        shell.run("boards", "state", "bx-14", "faulted", "--reason", "chain read as all ones");
        JsonNode body = shell.run("--json", "boards", "show", "bx-14").json();
        assertThat(body.get("state").asText()).isEqualTo("faulted");
        assertThat(body.get("state_reason").asText()).isEqualTo("chain read as all ones");
    }

    /** The command line reads the real clock, so the lease has to be live now. */
    @Test
    void faultingABoardGivesItsLeaseBack() {
        Lease lease;
        try (BenchDatabase other = new BenchDatabase(Shell.settings(url))) {
            lease = other.apply(unit -> Leasing.acquire(unit, "bx-14", "team-a", "capture", Times.now()));
        }
        Shell.Result result = shell.run("boards", "state", "bx-14", "faulted", "--reason", "smoke");
        assertThat(result.output()).contains(lease.id());
        assertThat(result.output()).contains("released");
    }
}
