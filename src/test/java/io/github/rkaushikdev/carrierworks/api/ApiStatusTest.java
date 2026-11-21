package io.github.rkaushikdev.carrierworks.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardSlot;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.services.Leasing;
import io.github.rkaushikdev.carrierworks.services.Scheduling;
import io.github.rkaushikdev.carrierworks.services.Thermal;
import io.github.rkaushikdev.carrierworks.services.Upkeep;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiStatusTest {

    private static final Instant WHEN = Instant.parse("2026-04-15T13:00:00Z");

    private static TestBench bench;
    private Http client;

    @BeforeAll
    static void start() {
        bench = TestBench.start(BenchSettings.builder().site("lab-a").build(), WHEN);
    }

    @AfterAll
    static void stop() {
        bench.close();
    }

    @BeforeEach
    void fresh() {
        bench.reset(WHEN);
        bench.database().run(unit -> {
            Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), WHEN.minus(Duration.ofDays(2)));
            Inventory.addBoard(unit, plain("bx-15"), WHEN.minus(Duration.ofDays(2)));
            Upkeep.logVisit(unit, "bx-14", "calibration", WHEN.minus(Duration.ofDays(1)), "sam");
        });
        client = bench.http();
    }

    private static Board plain(String asset) {
        return BoardSetup.artix(asset)
                .slots(new BoardSlot("pmod0", "pmod", "pmod-rs485"), new BoardSlot("fmc0", "fmc-lpc"))
                .build();
    }

    private static Map<String, Integer> counts(JsonNode tally) {
        return Http.JSON.convertValue(tally, new TypeReference<Map<String, Integer>>() {});
    }

    private static void takeBx14ForAnHour() {
        bench.database().run(unit -> Leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN, 60));
    }

    @Test
    void anIdleBenchReadsAsAllAvailable() {
        JsonNode body = client.get("/status").json();
        assertThat(counts(body.get("boards"))).isEqualTo(Map.of("available", 2));
        assertThat(body.get("available").asInt()).isEqualTo(2);
        assertThat(body.get("total_boards").asInt()).isEqualTo(2);
    }

    @Test
    void theRollUpCarriesItsOwnSentence() {
        assertThat(client.get("/status").json().get("summary").asText()).startsWith("lab-a: 2 of 2 free");
    }

    @Test
    void aLeaseMovesABoardOutOfAvailable() {
        takeBx14ForAnHour();
        JsonNode body = client.get("/status").json();
        assertThat(body.get("leases_held").asInt()).isEqualTo(1);
        assertThat(body.get("boards").get("leased").asInt()).isEqualTo(1);
    }

    @Test
    void theExpiringWindowCanBeWidened() {
        takeBx14ForAnHour();
        assertThat(client.get("/status").json().get("leases_expiring").asInt()).isZero();
        assertThat(client.get("/status?expiring_within=120")
                        .json()
                        .get("leases_expiring")
                        .asInt())
                .isEqualTo(1);
    }

    @Test
    void aSillyWindowIsRefused() {
        assertThat(client.get("/status?expiring_within=0").status()).isEqualTo(422);
    }

    @Test
    void aWaitingRequestShowsUpWithItsWait() {
        bench.database()
                .run(unit ->
                        Scheduling.submit(unit, "team-b", "capture", "device=xcvu9p", WHEN.minus(Duration.ofHours(2))));
        JsonNode body = client.get("/status").json();
        assertThat(body.get("queue_waiting").asInt()).isEqualTo(1);
        assertThat(body.get("queue_longest_wait_seconds").asLong()).isEqualTo(7200);
    }

    @Test
    void thermalStatesAreTallied() {
        bench.database().run(unit -> {
            Thermal.record(unit, "bx-14", WHEN.minus(Duration.ofSeconds(20)), 60_000);
            Thermal.record(unit, "bx-14", WHEN.minus(Duration.ofSeconds(5)), 61_000);
        });
        JsonNode body = client.get("/status").json();
        assertThat(counts(body.get("thermal"))).isEqualTo(Map.of("normal", 1, "unknown", 1));
    }

    @Test
    void aBoardOutOfCalibrationMakesTheBenchUnhealthy() {
        bench.database()
                .run(unit -> Inventory.addBoard(
                        unit,
                        BoardSetup.kintex("bx-16")
                                .location("rack-2/shelf-06/slot-2")
                                .serial("210308AA0001")
                                .outlet(12)
                                .build(),
                        WHEN.minus(Duration.ofDays(1))));
        JsonNode body = client.get("/status").json();
        assertThat(body.get("calibration_overdue")).extracting(JsonNode::asText).containsExactly("bx-16");
        assertThat(body.get("healthy").asBoolean()).isFalse();
    }

    @Test
    void aQuietBenchIsHealthy() {
        bench.database().run(unit -> {
            Thermal.record(unit, "bx-14", WHEN.minus(Duration.ofSeconds(10)), 60_000);
            Thermal.record(unit, "bx-15", WHEN.minus(Duration.ofSeconds(10)), 55_000);
        });
        JsonNode body = client.get("/status").json();
        assertThat(body.get("healthy").isBoolean()).isTrue();
        assertThat(body.get("healthy").asBoolean()).isTrue();
    }

    @Test
    void readinessAnswersWithoutAKey() {
        JsonNode body = client.get("/ready").json();
        assertThat(body.get("ready").asBoolean()).isTrue();
        assertThat(body.get("boards").asInt()).isEqualTo(2);
    }

    @Test
    void readinessReportsTheSite() {
        assertThat(client.get("/ready").json().get("site").asText()).isEqualTo("lab-a");
    }

    @Test
    void healthStaysTheShallowCheck() {
        JsonNode body = client.get("/health").json();
        assertThat(body.get("status").asText()).isEqualTo("ok");
        assertThat(body.has("boards")).isFalse();
    }
}
