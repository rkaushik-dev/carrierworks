package io.github.rkaushikdev.carrierworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardSlot;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.BenchOverview;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.services.Leasing;
import io.github.rkaushikdev.carrierworks.services.Overview;
import io.github.rkaushikdev.carrierworks.services.Scheduling;
import io.github.rkaushikdev.carrierworks.services.Thermal;
import io.github.rkaushikdev.carrierworks.services.Upkeep;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverviewTest {

    @TempDir
    Path tmp;

    private BenchDatabase database;
    private Shell shell;

    private static Board plain(String asset) {
        return BoardSetup.artix(asset)
                .slots(new BoardSlot("pmod0", "pmod", "pmod-rs485"), new BoardSlot("fmc0", "fmc-lpc"))
                .build();
    }

    private static Instant ago(Duration length) {
        return Times.now().minus(length);
    }

    @BeforeEach
    void database() {
        String url = Shell.url(tmp);
        database =
                new BenchDatabase(Shell.settings(url).toBuilder().site("lab-a").build());
        database.createSchema();
        database.run(unit -> {
            Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), ago(Duration.ofDays(2)));
            Inventory.addBoard(unit, plain("bx-15"), ago(Duration.ofDays(2)));
            Upkeep.logVisit(unit, "bx-14", "calibration", ago(Duration.ofDays(1)), "sam");
        });
        shell = new Shell(url);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private BenchOverview look() {
        return database.apply(unit -> Overview.overview(unit, Times.now()));
    }

    /** Readings for both boards, cool and fresh. */
    private void quiet() {
        database.run(unit -> {
            Thermal.record(unit, "bx-14", ago(Duration.ofSeconds(10)), 60_000);
            Thermal.record(unit, "bx-15", ago(Duration.ofSeconds(10)), 55_000);
        });
    }

    @Test
    void anIdleBenchIsAllAvailable() {
        BenchOverview state = look();
        assertThat(state.boards()).isEqualTo(Map.of("available", 2));
        assertThat(state.available()).isEqualTo(2);
        assertThat(state.totalBoards()).isEqualTo(2);
    }

    @Test
    void aLeaseShowsUpAsHeld() {
        database.run(unit -> Leasing.acquire(unit, "bx-14", "team-a", "capture", Times.now(), 60));
        BenchOverview state = look();
        assertThat(state.leasesHeld()).isEqualTo(1);
        assertThat(state.boards().get("leased")).isEqualTo(1);
    }

    @Test
    void aLeaseAboutToRunOutIsCountedSeparately() {
        database.run(unit -> Leasing.acquire(unit, "bx-14", "team-a", "capture", ago(Duration.ofMinutes(55))));
        assertThat(look().leasesExpiring()).isEqualTo(1);
    }

    @Test
    void aLeaseWithHoursLeftIsNotExpiring() {
        database.run(unit -> Leasing.acquire(unit, "bx-14", "team-a", "capture", Times.now(), 300));
        assertThat(look().leasesExpiring()).isZero();
    }

    @Test
    void aWaitingRequestIsCounted() {
        database.run(unit -> Scheduling.submit(unit, "team-b", "capture", "device=xcvu9p", ago(Duration.ofHours(1))));
        BenchOverview state = look();
        assertThat(state.queueWaiting()).isEqualTo(1);
        assertThat(state.queueLongestWaitSeconds()).isGreaterThan(3500).isLessThan(3700);
    }

    @Test
    void anEmptyQueueHasNoLongestWait() {
        assertThat(look().queueLongestWaitSeconds()).isZero();
    }

    @Test
    void thermalStatesAreTallied() {
        database.run(unit -> {
            Thermal.record(unit, "bx-14", ago(Duration.ofSeconds(20)), 60_000);
            Thermal.record(unit, "bx-14", ago(Duration.ofSeconds(5)), 61_000);
        });
        BenchOverview state = look();
        assertThat(state.thermal().get("normal")).isEqualTo(1);
        assertThat(state.thermal().get("unknown")).isEqualTo(1);
    }

    @Test
    void aBenchNobodyHasCalibratedIsNotHealthy() {
        database.run(unit -> Inventory.addBoard(
                unit,
                BoardSetup.kintex("bx-16")
                        .location("rack-2/shelf-06/slot-2")
                        .serial("210308AA0001")
                        .outlet(12)
                        .build(),
                ago(Duration.ofDays(1))));
        BenchOverview state = look();
        assertThat(state.calibrationOverdue()).containsExactly("bx-16");
        assertThat(state.healthy()).isFalse();
    }

    @Test
    void aQuietCalibratedBenchIsHealthy() {
        quiet();
        assertThat(look().healthy()).isTrue();
    }

    @Test
    void aFaultedBoardMakesTheBenchUnhealthy() {
        database.run(unit -> {
            Inventory.faultBoard(unit, "bx-14", Times.now(), "chain went away", "runner");
            Thermal.record(unit, "bx-14", ago(Duration.ofSeconds(10)), 60_000);
            Thermal.record(unit, "bx-15", ago(Duration.ofSeconds(10)), 55_000);
        });
        assertThat(look().healthy()).isFalse();
    }

    @Test
    void theLineReadsAsOneSentence() {
        assertThat(look().describe()).contains("lab-a: 2 of 2 free");
    }

    @Test
    void theCommandPrintsTheRollUp() {
        Shell.Result result = shell.run("status");
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("boards   available 2");
        assertThat(result.output()).contains("thermal  unknown 2");
    }

    @Test
    void theCommandGivesTheSameNumbersAsJson() {
        JsonNode body = shell.run("--json", "status").json();
        assertThat(body.get("available").numberValue()).isEqualTo(2);
        assertThat(body.get("queue_waiting").numberValue()).isEqualTo(0);
        assertThat(body.get("calibration_overdue").isArray()).isTrue();
        assertThat(body.get("calibration_overdue")).isEmpty();
    }

    @Test
    void theCommandCanFailWhenSomethingNeedsAPerson() {
        database.run(unit -> Inventory.faultBoard(unit, "bx-14", Times.now(), "chain went away", "runner"));
        assertThat(shell.run("status", "--fail-on-trouble").exitCode()).isEqualTo(2);
    }

    @Test
    void theCommandIsQuietWhenTheBenchIsFine() {
        quiet();
        assertThat(shell.run("status", "--fail-on-trouble").exitCode()).isZero();
    }
}
