package io.github.rkaushikdev.carrierworks.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiBoardsTest {

    private static final Instant WHEN = Instant.parse("2026-01-06T09:00:00Z");

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
        client = bench.http();
    }

    private static void seed(Board... boards) {
        bench.database().run(unit -> {
            for (Board board : boards) {
                Inventory.addBoard(unit, board, WHEN);
            }
        });
    }

    private static Map<String, Object> boardPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("asset", "bx-20");
        payload.put("model", "tx-carrier-a");
        payload.put("part", "XC7A100T-2FGG484I");
        payload.put("flash", "w25q64jv");
        payload.put("location", "rack-3/shelf-02/slot-1");
        payload.put("slots", List.of(Map.of("name", "fmc0", "connector", "fmc-lpc")));
        payload.put("adapter_serial", "210308EF9999");
        payload.put("adapter_model", "jtag-hs3");
        payload.put("pdu", "pdu-c/outlet-04");
        return payload;
    }

    private static List<String> assets(JsonNode listing) {
        return StreamSupport.stream(listing.spliterator(), false)
                .map(board -> board.get("asset").asText())
                .toList();
    }

    @Test
    void anEmptyBenchListsNothing() {
        JsonNode body = client.get("/boards").json();
        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    @Test
    void boardsComeBackWithTheirState() {
        seed(BoardSetup.kintex("bx-14").build());
        JsonNode body = client.get("/boards").json();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("asset").asText()).isEqualTo("bx-14");
        assertThat(body.get(0).get("device").asText()).isEqualTo("xc7k325t");
        assertThat(body.get(0).get("state").asText()).isEqualTo("available");
        assertThat(body.get(0).get("cards")).extracting(JsonNode::asText).containsExactly("fmc-adc-4ch-14b");
    }

    @Test
    void oneBoardComesBackOnItsOwn() {
        seed(BoardSetup.kintex("bx-14").build());
        JsonNode body = client.get("/boards/BX-0014").json();
        assertThat(body.get("location").asText()).isEqualTo("rack-2/shelf-04/slot-3");
        assertThat(body.get("transceivers_free").asInt()).isEqualTo(12);
    }

    @Test
    void aBoardNobodyHasIsA404() {
        Http.Answer response = client.get("/boards/bx-99");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.json().get("error").get("code").asText()).isEqualTo("not_found");
    }

    @Test
    void aBoardCanBeAdded() {
        Http.Answer response = client.postJson("/boards", boardPayload());
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("asset").asText()).isEqualTo("bx-20");
        assertThat(client.get("/boards/bx-20").json().get("state").asText()).isEqualTo("available");
    }

    @Test
    void aBoardThatCollidesIsAConflictShapedError() {
        seed(BoardSetup.kintex("bx-14").location("rack-3/shelf-02/slot-1").build());
        Http.Answer response = client.postJson("/boards", boardPayload());
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("error").get("message").asText()).contains("already in that slot");
    }

    @Test
    void aBoardWithABadPartIsRefused() {
        Map<String, Object> payload = boardPayload();
        payload.put("part", "XC7A100T");
        Http.Answer response = client.postJson("/boards", payload);
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().get("error").get("code").asText()).isEqualTo("validation");
    }

    @Test
    void theAvailableFilterLeavesOutTheBusyOnes() {
        seed(BoardSetup.kintex("bx-14").build(), BoardSetup.artix("bx-15").build());
        client.postJson("/boards/bx-14/state", Map.of("state", "leased"));
        assertThat(assets(client.get("/boards?available=true").json())).containsExactly("bx-15");
    }

    @Test
    void aRackFilterNarrowsTheListing() {
        seed(BoardSetup.kintex("bx-14").build(), BoardSetup.artix("bx-15").build());
        assertThat(assets(client.get("/boards?rack=rack-4").json())).containsExactly("bx-15");
    }

    @Test
    void aStateChangeGoesThroughTheStateMachine() {
        seed(BoardSetup.kintex("bx-14").build());
        JsonNode body = client.postJson("/boards/bx-14/state", Map.of("state", "leased"))
                .json();
        assertThat(body.get("state").asText()).isEqualTo("leased");
        Http.Answer refused = client.postJson("/boards/bx-14/state", Map.of("state", "quarantined", "reason", "no"));
        assertThat(refused.status()).isEqualTo(422);
    }

    @Test
    void faultingABoardRecordsTheReason() {
        seed(BoardSetup.kintex("bx-14").build());
        JsonNode body = client.postJson(
                        "/boards/bx-14/state",
                        Map.of("state", "faulted", "reason", "chain read as all ones", "actor", "nightly"))
                .json();
        assertThat(body.get("state").asText()).isEqualTo("faulted");
        assertThat(body.get("state_reason").asText()).isEqualTo("chain read as all ones");
    }

    @Test
    void aCardCanBeFittedAndRemoved() {
        seed(BoardSetup.kintex("bx-14").build());
        JsonNode fitted =
                client.put("/boards/bx-14/slots/fmc1?card=fmc-adc-2ch-16b").json();
        assertThat(fitted.get("cards"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("fmc-adc-2ch-16b", "fmc-adc-4ch-14b");
        JsonNode emptied = client.put("/boards/bx-14/slots/fmc1").json();
        assertThat(emptied.get("cards")).extracting(JsonNode::asText).containsExactly("fmc-adc-4ch-14b");
    }

    @Test
    void aCardThatDoesNotFitIsRefused() {
        seed(BoardSetup.kintex("bx-14").build());
        Http.Answer response = client.put("/boards/bx-14/slots/fmc1?card=fmc-sfp-quad");
        // A configuration error: the bench is wrong, not the caller.
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.json().get("error").get("message").asText()).contains("does not fit");
    }

    @Test
    void aBoardCanBeTakenOffTheBench() {
        seed(BoardSetup.kintex("bx-14").build());
        assertThat(client.delete("/boards/bx-14").status()).isEqualTo(204);
        assertThat(client.get("/boards").json()).isEmpty();
    }

    @Test
    void removingABoardNobodyHasIsA404() {
        assertThat(client.delete("/boards/bx-99").status()).isEqualTo(404);
    }
}
