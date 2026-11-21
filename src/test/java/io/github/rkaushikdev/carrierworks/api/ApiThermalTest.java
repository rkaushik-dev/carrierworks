package io.github.rkaushikdev.carrierworks.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiThermalTest {

    private static final Instant WHEN = Instant.parse("2026-03-18T11:00:00Z");
    private static final int EVERY = 20;

    private static TestBench bench;
    private Http client;

    @BeforeAll
    static void start() {
        bench = TestBench.start(BenchSettings.defaults(), WHEN);
    }

    @AfterAll
    static void stop() {
        bench.close();
    }

    @BeforeEach
    void fresh() {
        bench.reset(WHEN);
        bench.database().run(unit -> {
            Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), WHEN.minus(Duration.ofDays(1)));
            Inventory.addBoard(unit, BoardSetup.artix("bx-15").build(), WHEN.minus(Duration.ofDays(1)));
        });
        client = bench.http();
    }

    private Http.Answer post(int... readings) {
        return post("bx-14", Optional.empty(), readings);
    }

    private Http.Answer postFor(String board, int... readings) {
        return post(board, Optional.empty(), readings);
    }

    private Http.Answer postFrom(Instant start, int... readings) {
        return post("bx-14", Optional.of(start), readings);
    }

    /** A controller posting a batch, oldest reading first, one every twenty seconds up to now. */
    private Http.Answer post(String board, Optional<Instant> start, int... readings) {
        Instant first = start.orElse(WHEN.minus(Duration.ofSeconds((long) EVERY * (readings.length - 1))));
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int index = 0; index < readings.length; index++) {
            batch.add(Map.of(
                    "board",
                    board,
                    "millidegrees",
                    readings[index],
                    "at",
                    Times.iso(first.plus(Duration.ofSeconds((long) EVERY * index)))));
        }
        return client.postJson("/thermal/samples", batch);
    }

    private static Map<String, String> states(JsonNode body) {
        Map<String, String> states = new HashMap<>();
        body.forEach(entry ->
                states.put(entry.get("board").asText(), entry.get("state").asText()));
        return states;
    }

    private static List<Integer> millidegrees(JsonNode body) {
        return StreamSupport.stream(body.spliterator(), false)
                .map(entry -> entry.get("millidegrees").asInt())
                .toList();
    }

    @Test
    void aBatchOfReadingsIsStored() {
        Http.Answer response = post(60_000, 61_000, 62_000);
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json()).hasSize(3);
    }

    @Test
    void aStoredReadingComesBackInCelsiusToo() {
        JsonNode body = post(64_500).json();
        assertThat(body.get(0).get("celsius").asDouble()).isEqualTo(64.5);
        assertThat(body.get(0).get("millidegrees").asInt()).isEqualTo(64_500);
    }

    @Test
    void aReadingWithNoTimeIsStampedByTheServer() {
        Http.Answer response =
                client.postJson("/thermal/samples", List.of(Map.of("board", "bx-14", "millidegrees", 60_000)));
        assertThat(response.json().get(0).get("at").asText()).startsWith("2026-03-18T11:00:00");
    }

    @Test
    void aBatchNamingABoardNobodyHasIsA404() {
        Http.Answer response =
                client.postJson("/thermal/samples", List.of(Map.of("board", "bx-99", "millidegrees", 60_000)));
        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void oneBadReadingTakesTheWholeBatchWithIt() {
        client.postJson(
                "/thermal/samples",
                List.of(
                        Map.of("board", "bx-14", "millidegrees", 60_000),
                        Map.of("board", "bx-99", "millidegrees", 60_000)));
        assertThat(client.get("/boards/bx-14/thermal").json()).isEmpty();
    }

    @Test
    void anEmptyBatchIsRefused() {
        assertThat(client.postJson("/thermal/samples", List.of()).status()).isEqualTo(422);
    }

    @Test
    void aReadingOffTheScaleIsRefused() {
        Http.Answer response =
                client.postJson("/thermal/samples", List.of(Map.of("board", "bx-14", "millidegrees", 900_000)));
        assertThat(response.status()).isEqualTo(422);
    }

    @Test
    void statusCoversEveryBoardEvenTheSilentOnes() {
        post(60_000, 61_000);
        Map<String, String> states = states(client.get("/thermal/status").json());
        assertThat(states).containsOnlyKeys("bx-14", "bx-15");
        assertThat(states).containsEntry("bx-15", "unknown");
    }

    @Test
    void theWorstBoardComesFirst() {
        post(60_000, 61_000);
        postFor("bx-15", 97_000, 98_000);
        JsonNode body = client.get("/thermal/status").json();
        assertThat(body.get(0).get("board").asText()).isEqualTo("bx-15");
        assertThat(body.get(0).get("alarming").asBoolean()).isTrue();
    }

    @Test
    void aNormalBoardIsNotAlarming() {
        post(60_000, 61_000);
        JsonNode entry = StreamSupport.stream(
                        client.get("/thermal/status").json().spliterator(), false)
                .filter(row -> row.get("board").asText().equals("bx-14"))
                .findFirst()
                .orElseThrow();
        assertThat(entry.get("state").asText()).isEqualTo("normal");
        assertThat(entry.get("alarming").asBoolean()).isFalse();
    }

    @Test
    void theWindowCanBeNarrowed() {
        postFrom(WHEN.minus(Duration.ofHours(3)), 60_000, 61_000);
        Map<String, String> states =
                states(client.get("/thermal/status?window=15").json());
        assertThat(states).containsEntry("bx-14", "unknown");
    }

    @Test
    void aSillyWindowIsRefused() {
        assertThat(client.get("/thermal/status?window=0").status()).isEqualTo(422);
    }

    @Test
    void oneBoardHistoryComesBackOldestFirst() {
        post(60_000, 61_000, 62_000);
        assertThat(millidegrees(client.get("/boards/bx-14/thermal").json())).containsExactly(60_000, 61_000, 62_000);
    }

    @Test
    void historyForABoardNobodyHasIsA404() {
        assertThat(client.get("/boards/bx-99/thermal").status()).isEqualTo(404);
    }

    @Test
    void historyLeavesOutWhatIsOlderThanTheWindow() {
        postFrom(WHEN.minus(Duration.ofHours(4)), 55_000);
        post(60_000);
        assertThat(millidegrees(client.get("/boards/bx-14/thermal?window=60").json()))
                .containsExactly(60_000);
    }

    @Test
    void aTickOnAQuietBenchDoesNothing() {
        post(60_000, 61_000);
        postFor("bx-15", 55_000, 56_000);
        JsonNode body = client.post("/thermal/tick").json();
        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    @Test
    void aTickShutsAHotBoardDown() {
        post(101_000, 101_500);
        JsonNode body = client.post("/thermal/tick").json();
        assertThat(body.get(0).get("board").asText()).isEqualTo("bx-14");
        assertThat(body.get(0).get("action").asText()).isEqualTo("shutdown");
        assertThat(client.get("/boards/bx-14").json().get("state").asText()).isEqualTo("faulted");
    }

    @Test
    void aTickOnlyNotesAWarmBoard() {
        post(87_000, 87_500);
        JsonNode body = client.post("/thermal/tick").json();
        assertThat(body).extracting(entry -> entry.get("action").asText()).containsExactly("note");
        assertThat(client.get("/boards/bx-14").json().get("state").asText()).isEqualTo("available");
    }
}
