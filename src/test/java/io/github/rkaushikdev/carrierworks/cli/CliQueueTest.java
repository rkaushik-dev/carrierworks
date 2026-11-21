package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.services.Leasing;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliQueueTest {

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

    /** {@code queue submit} for a holder and what it needs, with any other options after. */
    private Shell.Result ask(String holder, String needs, String... rest) {
        List<String> args = new ArrayList<>(
                List.of("queue", "submit", "--holder", holder, "--purpose", "capture run", "--needs", needs));
        args.addAll(List.of(rest));
        return shell.run(args.toArray(String[]::new));
    }

    private Shell.Result ask() {
        return ask("team-a", "device=xc7k325t");
    }

    @Test
    void aRequestTakesAPlaceInTheLine() {
        Shell.Result result = ask();
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("req-0001 queued, 0 ahead of it");
    }

    @Test
    void theSecondRequestIsToldWhatIsAhead() {
        ask();
        assertThat(ask("team-b", "device=xc7k325t").output()).contains("1 ahead of it");
    }

    @Test
    void theListingShowsWhatWasAskedFor() {
        ask("team-a", "card=fmc-adc-4ch-14b,transceivers>=4");
        JsonNode body = shell.run("--json", "queue", "list").json();
        assertThat(body.get(0).get("needs").asText()).isEqualTo("card=fmc-adc-4ch-14b,transceivers>=4");
        assertThat(body.get(0).get("state").asText()).isEqualTo("waiting");
    }

    @Test
    void aRequestWithNoRequirementsSaysAnything() {
        ask("team-a", "");
        JsonNode body = shell.run("--json", "queue", "list").json();
        assertThat(body.get(0).get("needs").asText()).isEqualTo("anything");
    }

    @Test
    void theListingIsInTheOrderItWillBeServed() {
        ask("team-a", "device=xc7k325t", "--priority", "7");
        ask("team-b", "device=xc7k325t", "--priority", "2");
        JsonNode body = shell.run("--json", "queue", "list").json();
        assertThat(Shell.column(body, "holder")).containsExactly("team-b", "team-a");
    }

    @Test
    void theListingCanBeCutToOneTeam() {
        ask("team-a", "device=xc7k325t");
        ask("team-b", "device=xc7k325t");
        JsonNode body =
                shell.run("--json", "queue", "list", "--holder", "team-b").json();
        assertThat(Shell.column(body, "request")).containsExactly("req-0002");
    }

    @Test
    void aCancelledRequestLeavesTheLine() {
        ask();
        assertThat(shell.run("queue", "cancel", "req-0001").output()).contains("req-0001 cancelled");
        JsonNode body = shell.run("--json", "queue", "list").json();
        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    @Test
    void aCancelledRequestIsStillThereUnderAll() {
        ask();
        shell.run("queue", "cancel", "req-0001");
        JsonNode body = shell.run("--json", "queue", "list", "--all").json();
        assertThat(body.get(0).get("state").asText()).isEqualTo("cancelled");
    }

    @Test
    void cancellingARequestNobodyMadeIsAnError() {
        assertThat(shell.run("queue", "cancel", "req-0009").exitCode()).isNotZero();
    }

    @Test
    void aDispatchHandsOutABoard() {
        ask();
        Shell.Result result = shell.run("queue", "dispatch");
        assertThat(result.output()).contains("req-0001 -> bx-14");
    }

    @Test
    void aDispatchLeavesTheLeaseBehind() {
        ask();
        shell.run("queue", "dispatch");
        JsonNode body = shell.run("--json", "boards", "show", "bx-14").json();
        assertThat(body.get("held_by").asText()).isEqualTo("team-a");
    }

    @Test
    void aServedRequestShowsTheBoardItGot() {
        ask();
        shell.run("queue", "dispatch");
        JsonNode body = shell.run("--json", "queue", "list", "--all").json();
        assertThat(body.get(0).get("state").asText()).isEqualTo("served");
        assertThat(body.get(0).get("board").asText()).isEqualTo("bx-14");
    }

    @Test
    void aRequestForAPartNobodyHasWaits() {
        ask("team-a", "device=xcvu9p");
        JsonNode body = shell.run("--json", "queue", "dispatch").json();
        assertThat(body.get("assigned").isArray()).isTrue();
        assertThat(body.get("assigned")).isEmpty();
        assertThat(body.get("waiting").get("req-0001").asText()).contains("nothing free matches");
    }

    @Test
    void anEmptyQueueDispatchesToNothing() {
        assertThat(shell.run("queue", "dispatch").output()).contains("nothing waiting");
    }

    @Test
    void aDispatchCanBeLimited() {
        ask("team-a", "device=xc7k325t");
        ask("team-b", "device=xc7a100t");
        JsonNode body = shell.run("--json", "queue", "dispatch", "--limit", "1").json();
        assertThat(body.get("assigned")).hasSize(1);
    }

    @Test
    void aQuotaHoldsATeamBack() {
        database.run(unit -> Leasing.acquire(
                unit, "bx-15", "team-a", "earlier run", Times.now().minus(Duration.ofMinutes(5))));
        ask("team-a", "device=xc7k325t");
        JsonNode body =
                shell.run("--json", "queue", "dispatch", "--quota", "team-a=1").json();
        assertThat(body.get("waiting").get("req-0001").asText()).contains("quota is 1");
    }

    @Test
    void aQuotaThatIsNotANumberIsAUsageError() {
        ask();
        Shell.Result result = shell.run("queue", "dispatch", "--quota", "team-a=lots");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("team=count");
    }

    @Test
    void twoTeamsAskingForDifferentPartsAreBothServed() {
        ask("team-a", "device=xc7k325t");
        ask("team-b", "device=xc7a100t");
        JsonNode body = shell.run("--json", "queue", "dispatch").json();
        assertThat(Shell.column(body.get("assigned"), "board")).containsOnly("bx-14", "bx-15");
    }
}
