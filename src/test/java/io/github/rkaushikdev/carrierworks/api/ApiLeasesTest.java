package io.github.rkaushikdev.carrierworks.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiLeasesTest {

    private static final Instant WHEN = Instant.parse("2026-01-12T09:00:00Z");

    private static TestBench bench;
    private Http client;

    @BeforeAll
    static void start() {
        bench = TestBench.start(BenchSettings.builder().maxLeaseMinutes(240).build(), WHEN);
    }

    @AfterAll
    static void stop() {
        bench.close();
    }

    @BeforeEach
    void fresh() {
        bench.reset(WHEN);
        bench.database().run(unit -> {
            Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), WHEN);
            Inventory.addBoard(unit, BoardSetup.artix("bx-15").build(), WHEN);
        });
        client = bench.http();
    }

    private Http.Answer take() {
        return take("bx-14", Map.of());
    }

    private Http.Answer take(String tag, Map<String, Object> changes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("holder", "team-a");
        payload.put("purpose", "capture regression");
        payload.putAll(changes);
        return client.postJson("/boards/" + tag + "/lease", payload);
    }

    private static void tick(Duration by) {
        bench.clock().advance(by);
    }

    @Test
    void takingABoardGivesALease() {
        Http.Answer response = take();
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("id").asText()).isEqualTo("lease-00001");
        assertThat(body.get("board").asText()).isEqualTo("bx-14");
        assertThat(body.get("holder").asText()).isEqualTo("team-a");
    }

    @Test
    void takingABoardMarksItLeased() {
        take();
        assertThat(client.get("/boards/bx-14").json().get("state").asText()).isEqualTo("leased");
    }

    @Test
    void aBoardCannotBeTakenTwice() {
        take();
        Http.Answer response = take("bx-14", Map.of("holder", "team-b"));
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("error").get("details").get("holder").asText())
                .isEqualTo("team-a");
    }

    @Test
    void aBoardOutOfServiceCannotBeTaken() {
        client.postJson("/boards/bx-14/state", Map.of("state", "faulted", "reason", "chain"));
        Http.Answer response = take();
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("error").get("details").get("state").asText())
                .isEqualTo("faulted");
    }

    @Test
    void aLeaseLongerThanTheCeilingIsRefused() {
        Http.Answer response = take("bx-14", Map.of("minutes", 600));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("error").get("details").get("ceiling").asInt())
                .isEqualTo(240);
    }

    @Test
    void idsCarryOnAcrossBoards() {
        assertThat(take().json().get("id").asText()).isEqualTo("lease-00001");
        assertThat(take("bx-15", Map.of()).json().get("id").asText()).isEqualTo("lease-00002");
    }

    @Test
    void theLiveLeasesAreListed() {
        take();
        take("bx-15", Map.of("holder", "team-b"));
        JsonNode listed = client.get("/leases").json();
        assertThat(listed).extracting(entry -> entry.get("board").asText()).containsExactlyInAnyOrder("bx-14", "bx-15");
        JsonNode mine = client.get("/leases?holder=team-b").json();
        assertThat(mine).extracting(entry -> entry.get("board").asText()).containsExactly("bx-15");
    }

    @Test
    void aBoardSaysWhatHoldsIt() {
        assertThat(client.get("/boards/bx-14/lease").json().isNull()).isTrue();
        take();
        assertThat(client.get("/boards/bx-14/lease").json().get("holder").asText())
                .isEqualTo("team-a");
    }

    @Test
    void aBoardNobodyHasIsA404() {
        assertThat(client.get("/boards/bx-99/lease").status()).isEqualTo(404);
    }

    @Test
    void releasingALeaseFreesTheBoard() {
        JsonNode lease = take().json();
        JsonNode released = client.post("/leases/" + lease.get("id").asText() + "/release?reason=finished")
                .json();
        assertThat(released.get("release_reason").asText()).isEqualTo("finished");
        assertThat(client.get("/boards/bx-14").json().get("state").asText()).isEqualTo("available");
        assertThat(client.get("/boards/bx-14/lease").json().isNull()).isTrue();
    }

    @Test
    void aReleasedLeaseCannotBeReleasedAgain() {
        String id = take().json().get("id").asText();
        client.post("/leases/" + id + "/release");
        assertThat(client.post("/leases/" + id + "/release").status()).isEqualTo(422);
    }

    @Test
    void renewingPushesTheExpiryOut() {
        JsonNode lease = take("bx-14", Map.of("minutes", 30)).json();
        tick(Duration.ofMinutes(25));
        JsonNode renewed = client.postJson("/leases/" + lease.get("id").asText() + "/renew", Map.of("minutes", 60))
                .json();
        assertThat(renewed.get("renewals").asInt()).isEqualTo(1);
        assertThat(renewed.get("expires_at").asText())
                .isGreaterThan(lease.get("expires_at").asText());
    }

    @Test
    void renewalStopsAtTheCeiling() {
        JsonNode lease = take("bx-14", Map.of("minutes", 60)).json();
        tick(Duration.ofMinutes(30));
        JsonNode renewed = client.postJson("/leases/" + lease.get("id").asText() + "/renew", Map.of("minutes", 600))
                .json();
        assertThat(renewed.get("expires_at").asText()).startsWith("2026-01-12T13:00");
    }

    @Test
    void aLeaseNobodyIssuedIsA404() {
        assertThat(client.get("/leases/lease-09999").status()).isEqualTo(404);
        assertThat(client.postJson("/leases/lease-09999/renew", Map.of("minutes", 10))
                        .status())
                .isEqualTo(404);
    }

    @Test
    void theSweepClosesWhatHasRunOut() {
        take("bx-14", Map.of("minutes", 30));
        tick(Duration.ofMinutes(31));
        JsonNode swept = client.post("/leases/sweep").json();
        assertThat(swept)
                .extracting(entry -> entry.get("release_reason").asText())
                .containsExactly("expired");
        assertThat(client.get("/boards/bx-14").json().get("state").asText()).isEqualTo("available");
        assertThat(client.post("/leases/sweep").json()).isEmpty();
    }

    @Test
    void aBoardSweptFreeCanBeTakenAgain() {
        take("bx-14", Map.of("minutes", 30));
        tick(Duration.ofMinutes(31));
        client.post("/leases/sweep");
        assertThat(take("bx-14", Map.of("holder", "team-b")).status()).isEqualTo(201);
    }
}
