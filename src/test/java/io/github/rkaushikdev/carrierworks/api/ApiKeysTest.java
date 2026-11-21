package io.github.rkaushikdev.carrierworks.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Role;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.services.Security;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiKeysTest {

    private static final Instant WHEN = Instant.parse("2026-01-28T09:00:00Z");
    private static final Map<String, Object> TAKE = Map.of("holder", "team-a", "purpose", "capture");

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
        bench.database()
                .run(unit -> Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), WHEN));
        client = bench.http();
    }

    /** Issue a key straight into the database and hand back a client presenting it. */
    private Http issue(String label, String role, String secret) {
        bench.database().run(unit -> Security.issue(unit, label, Role.parse(role), WHEN, "", secret));
        return client.as(secret);
    }

    private static Map<String, Object> boardPayload() {
        return Map.of(
                "asset", "bx-30",
                "model", "tx-carrier-a",
                "part", "XC7A100T-2FGG484I",
                "flash", "w25q64jv",
                "location", "rack-3/shelf-05/slot-2");
    }

    @Test
    void aBenchWithNoKeysIsOpen() {
        assertThat(client.get("/boards").status()).isEqualTo(200);
        assertThat(client.postJson("/boards", boardPayload()).status()).isEqualTo(201);
    }

    @Test
    void theFirstKeyClosesTheBench() {
        issue("dashboard", "reader", "reader-secret");
        assertThat(client.postJson("/boards", boardPayload()).status()).isEqualTo(401);
    }

    @Test
    void aReaderCanRead() {
        Http reader = issue("dashboard", "reader", "reader-secret");
        assertThat(reader.get("/boards").status()).isEqualTo(200);
    }

    @Test
    void aReaderCannotTakeABoard() {
        Http reader = issue("dashboard", "reader", "reader-secret");
        Http.Answer response = reader.postJson("/boards/bx-14/lease", TAKE);
        assertThat(response.status()).isEqualTo(403);
        assertThat(Http.JSON.convertValue(
                        response.json().get("error").get("details"), new TypeReference<Map<String, Object>>() {}))
                .isEqualTo(Map.of("needs", "operator", "holds", "reader"));
    }

    @Test
    void anOperatorCanTakeABoard() {
        Http operator = issue("nightly", "operator", "operator-secret");
        assertThat(operator.postJson("/boards/bx-14/lease", TAKE).status()).isEqualTo(201);
    }

    @Test
    void anOperatorCannotAddABoard() {
        Http operator = issue("nightly", "operator", "operator-secret");
        Http.Answer response = operator.postJson("/boards", boardPayload());
        assertThat(response.status()).isEqualTo(403);
        assertThat(response.json().get("error").get("details").get("needs").asText())
                .isEqualTo("admin");
    }

    @Test
    void anAdminCanDoBoth() {
        Http admin = issue("ops", "admin", "admin-secret");
        assertThat(admin.postJson("/boards", boardPayload()).status()).isEqualTo(201);
        assertThat(admin.postJson("/boards/bx-14/lease", TAKE).status()).isEqualTo(201);
    }

    @Test
    void aKeyNobodyIssuedIsRefused() {
        issue("ops", "admin", "admin-secret");
        Http.Answer response = client.as("made-up").get("/boards");
        assertThat(response.status()).isEqualTo(401);
        assertThat(response.json().get("error").get("code").asText()).isEqualTo("unauthorized");
    }

    @Test
    void aRevokedKeyStopsWorking() {
        Http admin = issue("old laptop", "admin", "old-secret");
        String keyId = admin.get("/keys").json().get(0).get("id").asText();
        admin.post("/keys/" + keyId + "/revoke");
        assertThat(admin.get("/boards").status()).isEqualTo(403);
    }

    @Test
    void aKeyCanBeIssuedOverHttp() {
        Http admin = issue("ops", "admin", "admin-secret");
        Http.Answer response = admin.postJson(
                "/keys", Map.of("label", "nightly runner", "role", "operator", "team", "signal-processing"));
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("role").asText()).isEqualTo("operator");
        assertThat(body.get("secret").asText()).isNotEmpty();
        Http fresh = client.as(body.get("secret").asText());
        assertThat(fresh.get("/keys/mine").json().get("label").asText()).isEqualTo("nightly runner");
    }

    @Test
    void theSecretOnlyComesBackOnce() {
        Http admin = issue("ops", "admin", "admin-secret");
        admin.postJson("/keys", Map.of("label", "runner", "role", "operator"));
        JsonNode listed = admin.get("/keys").json();
        assertThat(listed).hasSize(2).allSatisfy(entry -> assertThat(entry.has("secret"))
                .isFalse());
    }

    @Test
    void onlyAnAdminCanListKeys() {
        Http operator = issue("nightly", "operator", "operator-secret");
        assertThat(operator.get("/keys").status()).isEqualTo(403);
    }

    @Test
    void aKeyCanReadItsOwnRole() {
        Http operator = issue("nightly", "operator", "operator-secret");
        JsonNode body = operator.get("/keys/mine").json();
        assertThat(body.get("role").asText()).isEqualTo("operator");
        assertThat(body.get("label").asText()).isEqualTo("nightly");
    }

    @Test
    void theOpenBenchReadsAsAnAdmin() {
        JsonNode body = client.get("/keys/mine").json();
        assertThat(body.get("role").asText()).isEqualTo("admin");
        assertThat(body.get("label").asText()).isEqualTo("open bench");
    }
}
