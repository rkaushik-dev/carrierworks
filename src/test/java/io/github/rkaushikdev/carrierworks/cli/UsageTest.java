package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.services.Leasing;
import io.github.rkaushikdev.carrierworks.services.Scheduling;
import io.github.rkaushikdev.carrierworks.services.Usage;
import io.github.rkaushikdev.carrierworks.services.UsageReport;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsageTest {

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
                    unit, BoardSetup.kintex("bx-14").build(), Times.now().minus(Duration.ofDays(60)));
            Inventory.addBoard(
                    unit, BoardSetup.artix("bx-15").build(), Times.now().minus(Duration.ofDays(60)));
        });
        shell = new Shell(url);
    }

    @AfterEach
    void close() {
        database.close();
    }

    /** A lease that ran for a while and was given back. */
    private Lease held(String board, String holder, long hoursAgo, int minutes) {
        Instant taken = Times.now().minus(Duration.ofHours(hoursAgo));
        return database.apply(unit -> {
            Lease lease = Leasing.acquire(unit, board, holder, "capture", taken, minutes);
            Leasing.release(unit, lease.id(), taken.plus(Duration.ofMinutes(minutes)), "done");
            return lease;
        });
    }

    private Lease held(String board, String holder, int minutes) {
        return held(board, holder, 48, minutes);
    }

    private Lease held(String board) {
        return held(board, "team-a", 120);
    }

    private UsageReport look() {
        return database.apply(unit -> Usage.usage(unit, Times.now()));
    }

    private UsageReport look(Duration window) {
        return database.apply(unit -> Usage.usage(unit, Times.now(), window));
    }

    @Test
    void aBenchNobodyUsedIsAllIdle() {
        UsageReport report = look();
        assertThat(report.idle()).containsOnly("bx-14", "bx-15");
        assertThat(report.leases()).isZero();
        assertThat(report.busiest()).isEmpty();
    }

    @Test
    void everyBoardIsListedEvenTheUnusedOnes() {
        held("bx-14");
        assertThat(look().utilisation().keySet()).containsOnly("bx-14", "bx-15");
    }

    @Test
    void aTwoHourLeaseOverAWeekIsAboutOnePercent() {
        held("bx-14");
        double fraction = look().utilisation().get("bx-14");
        assertThat(fraction).isGreaterThan(0.010).isLessThan(0.014);
    }

    @Test
    void theHoursAreCountedAgainstTheTeam() {
        held("bx-14", "team-a", 180);
        assertThat(look().holderHours().get("team-a")).isEqualTo(3.0);
    }

    @Test
    void twoTeamsAreCountedApart() {
        held("bx-14", "team-a", 60);
        held("bx-15", "team-b", 120);
        assertThat(look().holderHours()).isEqualTo(Map.of("team-a", 1.0, "team-b", 2.0));
    }

    @Test
    void aLeaseOutsideTheWindowDoesNotCount() {
        held("bx-14", "team-a", 24 * 40, 120);
        UsageReport report = look();
        assertThat(report.leases()).isZero();
        assertThat(report.holderHours()).isEmpty();
    }

    @Test
    void aLeaseThatStraddlesTheEdgeCountsOnlyItsOverlap() {
        held("bx-14", "team-a", 24 * 7 + 1, 120);
        UsageReport report = look(Duration.ofDays(7));
        assertThat(report.holderHours().get("team-a")).isGreaterThan(0.0).isLessThan(1.1);
    }

    @Test
    void aLongerWindowPullsInOlderLeases() {
        held("bx-14", "team-a", 24 * 20, 120);
        assertThat(look(Duration.ofDays(30)).leases()).isEqualTo(1);
    }

    @Test
    void theBusiestBoardIsTheOneHeldLongest() {
        held("bx-14", "team-a", 60);
        held("bx-15", "team-a", 300);
        assertThat(look().busiest()).contains("bx-15");
    }

    @Test
    void anIdleBoardIsNamed() {
        held("bx-14");
        assertThat(look().idle()).containsExactly("bx-15");
    }

    @Test
    void aWindowWithNoLengthIsRefused() {
        assertThatThrownBy(() -> look(Duration.ZERO)).isInstanceOf(ValidationError.class);
    }

    @Test
    void theWaitingRequestsAreCounted() {
        database.run(unit -> Scheduling.submit(unit, "team-b", "capture", "device=xcvu9p", Times.now()));
        assertThat(look().waiting()).isEqualTo(1);
    }

    @Test
    void theReportReadsAsOneLine() {
        held("bx-14");
        String line = look().describe();
        assertThat(line).contains("2 boards over 7.0 days, 1 leases");
        assertThat(line).contains("idle: bx-15");
    }

    @Test
    void theBoardListingPutsTheBusiestFirst() {
        held("bx-15", "team-a", 300);
        held("bx-14", "team-a", 60);
        JsonNode body = shell.run("--json", "usage", "boards").json();
        assertThat(Shell.column(body, "asset")).containsExactly("bx-15", "bx-14");
    }

    @Test
    void theBoardListingShowsHours() {
        held("bx-14", "team-a", 120);
        JsonNode body = shell.run("--json", "usage", "boards").json();
        assertThat(body.get(0).get("hours").numberValue()).isEqualTo(2.0);
    }

    @Test
    void theTeamListingIsOrderedByHours() {
        held("bx-14", "team-a", 60);
        held("bx-15", "team-b", 180);
        JsonNode body = shell.run("--json", "usage", "teams").json();
        assertThat(Shell.column(body, "holder")).containsExactly("team-b", "team-a");
    }

    @Test
    void theIdleCommandNamesTheBoards() {
        held("bx-14");
        JsonNode body = shell.run("--json", "usage", "idle").json();
        assertThat(Shell.texts(body.get("idle"))).containsExactly("bx-15");
    }

    @Test
    void theIdleWindowCanBeWidened() {
        held("bx-14", "team-a", 24 * 20, 120);
        assertThat(Shell.texts(shell.run("--json", "usage", "idle", "--days", "7")
                        .json()
                        .get("idle")))
                .containsExactly("bx-14", "bx-15");
        assertThat(Shell.texts(shell.run("--json", "usage", "idle", "--days", "30")
                        .json()
                        .get("idle")))
                .containsExactly("bx-15");
    }

    @Test
    void theSummaryCarriesTheNumbersBehindTheLine() {
        held("bx-14");
        JsonNode body = shell.run("--json", "usage", "summary").json();
        assertThat(body.get("leases").numberValue()).isEqualTo(1);
        assertThat(body.get("busiest").asText()).isEqualTo("bx-14");
        assertThat(Shell.texts(body.get("idle"))).containsExactly("bx-15");
    }

    @Test
    void theSummaryPrintsOneLineForAPerson() {
        held("bx-14");
        Shell.Result result = shell.run("usage", "summary");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("busiest bx-14");
    }
}
