package io.github.rkaushikdev.carrierworks.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.services.Leasing;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiQueueTest {

    private static final Instant WHEN = Instant.parse("2026-04-02T10:00:00Z");

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

    private Http.Answer ask() {
        return ask("team-a", "device=xc7k325t", Map.of());
    }

    private Http.Answer ask(String holder) {
        return ask(holder, "device=xc7k325t", Map.of());
    }

    private Http.Answer ask(String holder, String needs, Map<String, Object> rest) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("holder", holder);
        body.put("purpose", "capture run");
        body.put("needs", needs);
        body.putAll(rest);
        return client.postJson("/requests", body);
    }

    private Http.Answer dispatch(String query, Map<String, Object> quotas) {
        return client.postJson("/requests/dispatch" + query, quotas);
    }

    @Test
    void aRequestGoesOnTheLine() {
        Http.Answer response = ask();
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("id").asText()).isEqualTo("req-0001");
        assertThat(body.get("state").asText()).isEqualTo("waiting");
        assertThat(body.get("place").asInt()).isEqualTo(1);
    }

    @Test
    void aRequestKeepsTheWordsItWasWrittenIn() {
        JsonNode body =
                ask("team-a", "card=fmc-adc-4ch-14b,transceivers>=4", Map.of()).json();
        assertThat(body.get("needs"))
                .extracting(JsonNode::asText)
                .containsExactly("card=fmc-adc-4ch-14b", "transceivers>=4");
    }

    @Test
    void aPriorityOutsideTheRangeIsRefused() {
        Http.Answer response = ask("team-a", "device=xc7k325t", Map.of("priority", 0));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json()
                        .get("error")
                        .get("details")
                        .get("problems")
                        .get(0)
                        .get("where")
                        .asText())
                .isEqualTo("priority");
    }

    @Test
    void aRequestWithNoPurposeIsRefused() {
        assertThat(client.postJson("/requests", Map.of("holder", "team-a", "purpose", " "))
                        .status())
                .isEqualTo(422);
    }

    @Test
    void theLineComesBackInServingOrder() {
        ask("team-a", "device=xc7k325t", Map.of("priority", 7));
        ask("team-b", "device=xc7k325t", Map.of("priority", 2));
        JsonNode body = client.get("/requests").json();
        assertThat(body).extracting(entry -> entry.get("holder").asText()).containsExactly("team-b", "team-a");
    }

    @Test
    void theLineCanBeCutToOneTeam() {
        ask("team-a");
        ask("team-b");
        JsonNode body = client.get("/requests?holder=team-b").json();
        assertThat(body).extracting(entry -> entry.get("id").asText()).containsExactly("req-0002");
    }

    @Test
    void oneRequestReadsBackWithItsPlace() {
        ask("team-a");
        ask("team-b");
        JsonNode body = client.get("/requests/req-0002").json();
        assertThat(body.get("place").asInt()).isEqualTo(2);
        assertThat(body.get("waited_seconds").asLong()).isZero();
    }

    @Test
    void aRequestNobodyMadeIsA404() {
        assertThat(client.get("/requests/req-0099").status()).isEqualTo(404);
    }

    @Test
    void aCancelledRequestLeavesTheLine() {
        ask();
        assertThat(client.delete("/requests/req-0001").json().get("state").asText())
                .isEqualTo("cancelled");
        assertThat(client.get("/requests").json()).isEmpty();
    }

    @Test
    void aCancelledRequestIsStillThereUnderClosed() {
        ask();
        client.delete("/requests/req-0001");
        JsonNode body = client.get("/requests?closed=true").json();
        assertThat(body).extracting(entry -> entry.get("state").asText()).containsExactly("cancelled");
    }

    @Test
    void cancellingTwiceIsA422() {
        ask();
        client.delete("/requests/req-0001");
        assertThat(client.delete("/requests/req-0001").status()).isEqualTo(422);
    }

    @Test
    void aDispatchHandsOutABoard() {
        ask();
        JsonNode body = dispatch("", Map.of()).json();
        assertThat(Http.JSON.convertValue(body.get("assigned"), new TypeReference<List<Map<String, Object>>>() {}))
                .containsExactly(Map.of("request", "req-0001", "board", "bx-14", "lease", "lease-00001"));
    }

    @Test
    void aServedRequestShowsItsBoard() {
        ask();
        dispatch("", Map.of());
        JsonNode body = client.get("/requests/req-0001").json();
        assertThat(body.get("state").asText()).isEqualTo("served");
        assertThat(body.get("board").asText()).isEqualTo("bx-14");
        assertThat(body.get("place").isNull()).isTrue();
    }

    @Test
    void aDispatchThatFindsNothingSaysWhy() {
        ask("team-a", "device=xcvu9p", Map.of());
        JsonNode body = dispatch("", Map.of()).json();
        assertThat(body.get("assigned")).isEmpty();
        assertThat(body.get("waiting").get("req-0001").asText()).contains("nothing free matches");
    }

    @Test
    void aDispatchCanBeLimited() {
        ask("team-a", "device=xc7k325t", Map.of());
        ask("team-b", "device=xc7a100t", Map.of());
        JsonNode body = dispatch("?limit=1", Map.of()).json();
        assertThat(body.get("assigned")).hasSize(1);
    }

    @Test
    void aQuotaHoldsATeamBack() {
        bench.database()
                .run(unit -> Leasing.acquire(unit, "bx-15", "team-a", "earlier", WHEN.minus(Duration.ofMinutes(5))));
        ask("team-a");
        JsonNode body = dispatch("", Map.of("team-a", 1)).json();
        assertThat(body.get("waiting").get("req-0001").asText()).contains("quota is 1");
    }

    @Test
    void aDispatchTakesALeaseTheBoardRouteCanSee() {
        ask();
        dispatch("", Map.of());
        assertThat(client.get("/boards/bx-14/lease").json().get("holder").asText())
                .isEqualTo("team-a");
    }
}
