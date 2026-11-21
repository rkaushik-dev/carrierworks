package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.services.Leasing;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliLeasesTest {

    @TempDir
    Path tmp;

    private String url;
    private BenchDatabase database;
    private Shell shell;

    @BeforeEach
    void bench() {
        url = Shell.url(tmp);
        database = Shell.migrated(url);
        // a day ago, so a test can put a lease in the recent past
        Instant arrived = Times.now().minus(Duration.ofDays(1));
        database.run(unit -> {
            Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), arrived);
            Inventory.addBoard(unit, BoardSetup.artix("bx-15").build(), arrived);
        });
        shell = new Shell(url);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Shell.Result take(String... extra) {
        List<String> args =
                new ArrayList<>(List.of("lease", "take", "--holder", "team-a", "--purpose", "capture regression"));
        args.addAll(List.of(extra));
        return shell.run(args.toArray(String[]::new));
    }

    @Test
    void takingANamedBoardSaysUntilWhen() {
        Shell.Result result = take("--board", "bx-14");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("bx-14 is yours until");
        assertThat(result.output()).contains("lease-00001");
    }

    @Test
    void takingABoardMarksItLeased() {
        take("--board", "bx-14");
        var body = shell.run("--json", "boards", "show", "bx-14").json();
        assertThat(body.get("state").asText()).isEqualTo("leased");
        assertThat(body.get("held_by").asText()).isEqualTo("team-a");
    }

    @Test
    void aBoardSomebodyElseHasIsRefusedWithTheirName() {
        take("--board", "bx-14");
        Shell.Result result =
                shell.run("lease", "take", "--board", "bx-14", "--holder", "team-b", "--purpose", "bring up");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("already leased");
        assertThat(result.output()).contains("team-a");
    }

    @Test
    void aBoardCanBeAskedForByWhatItNeeds() {
        Shell.Result result = take("--needs", "adc-14bit");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("bx-14 is yours");
    }

    @Test
    void askingForTheSmallestThingThatFitsGetsTheSmallBoard() {
        Shell.Result result = take("--needs", "jtag");
        assertThat(result.output()).contains("bx-15 is yours");
    }

    @Test
    void askingForSomethingNothingHasSaysWhyEachBoardFailed() {
        Shell.Result result = take("--needs", "ddr4-sodimm");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("nothing free matches");
        assertThat(result.output()).contains("no ddr4-sodimm");
    }

    @Test
    void askingForNeitherABoardNorARequirementIsAUsageError() {
        Shell.Result result = take();
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("say which board");
    }

    @Test
    void theLeaseListingShowsWhoHasWhat() {
        take("--board", "bx-14");
        Shell.Result result = shell.run("lease", "list");
        assertThat(result.output()).contains("LEASE");
        assertThat(result.output()).contains("team-a");
        assertThat(result.output()).contains("capture regression");
    }

    @Test
    void theListingCanBeNarrowedToOneHolder() {
        take("--board", "bx-14");
        shell.run("lease", "take", "--board", "bx-15", "--holder", "team-b", "--purpose", "bring up");
        var body = shell.run("--json", "lease", "list", "--holder", "team-b").json();
        assertThat(Shell.column(body, "board")).containsExactly("bx-15");
    }

    @Test
    void anEmptyBenchListsNothing() {
        assertThat(shell.run("lease", "list").output()).contains("nothing to show");
    }

    @Test
    void releasingFreesTheBoard() {
        take("--board", "bx-14");
        Shell.Result result = shell.run("lease", "release", "lease-00001", "--reason", "finished");
        assertThat(result.output()).contains("bx-14 is free again");
        assertThat(shell.run("--json", "boards", "show", "bx-14")
                        .json()
                        .get("state")
                        .asText())
                .isEqualTo("available");
    }

    @Test
    void releasingALeaseNobodyIssuedIsAnError() {
        Shell.Result result = shell.run("lease", "release", "lease-09999");
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("no lease known as lease-09999");
    }

    @Test
    void renewingPushesTheExpiryOut() {
        take("--board", "bx-14", "--minutes", "30");
        Instant before = Times.parse(
                shell.run("--json", "lease", "list").json().get(0).get("until").asText());
        Shell.Result result = shell.run("--json", "lease", "renew", "lease-00001", "--minutes", "120");
        Instant after = Times.parse(result.json().get("expires_at").asText());
        assertThat(after).isAfter(before);
    }

    @Test
    void aKeptLeaseIsNotPreemptible() {
        take("--board", "bx-14", "--keep");
        Lease lease;
        try (BenchDatabase other = new BenchDatabase(Shell.settings(url))) {
            lease = other.apply(unit -> unit.leases().get("lease-00001"));
        }
        assertThat(lease.preemptible()).isFalse();
    }

    @Test
    void theSweepSaysWhenThereWasNothingToDo() {
        assertThat(shell.run("lease", "sweep").output()).contains("nothing had run out");
    }

    @Test
    void theSweepClosesWhatHasRunOut() {
        try (BenchDatabase other = new BenchDatabase(Shell.settings(url))) {
            other.run(unit -> Leasing.acquire(
                    unit, "bx-14", "team-a", "capture", Times.now().minus(Duration.ofHours(3)), 30));
        }
        Shell.Result result = shell.run("lease", "sweep");
        assertThat(result.output()).contains("bx-14");
        assertThat(shell.run("--json", "boards", "show", "bx-14")
                        .json()
                        .get("state")
                        .asText())
                .isEqualTo("available");
    }
}
