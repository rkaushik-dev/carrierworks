package io.github.rkaushikdev.carrierworks.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.BenchError;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.services.Leasing;
import io.github.rkaushikdev.carrierworks.testroutes.RaisingRoutes;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiErrorsTest {

    private static final Instant WHEN = Instant.parse("2026-06-16T08:00:00Z");

    private static TestBench bench;
    private Http client;

    @BeforeAll
    static void start() {
        bench = TestBench.start(BenchSettings.defaults(), WHEN, RaisingRoutes.class);
    }

    @AfterAll
    static void stop() {
        bench.close();
    }

    @BeforeEach
    void fresh() {
        bench.reset(WHEN);
        bench.database()
                .run(unit ->
                        Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), WHEN.minus(Duration.ofDays(1))));
        client = bench.http();
    }

    @Test
    void aTransientFailureSaysWhenToComeBack() {
        Http.Answer response = client.get("/boom/transient");
        assertThat(response.status()).isEqualTo(503);
        assertThat(response.header("retry-after")).contains("10");
    }

    @Test
    void aChainFailureSaysComeBackSoon() {
        assertThat(client.get("/boom/chain").header("retry-after")).contains("5");
    }

    @Test
    void aPowerFailureSaysComeBackLater() {
        assertThat(client.get("/boom/power").header("retry-after")).contains("30");
    }

    @Test
    void aConflictDoesNotSayToRetry() {
        Http.Answer response = client.get("/boom/conflict");
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.header("retry-after")).isEmpty();
    }

    @Test
    void aValidationErrorDoesNotSayToRetry() {
        Http.Answer response = client.get("/boom/validation");
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.header("retry-after")).isEmpty();
    }

    @Test
    void everyErrorCarriesItsCodeAndMessage() {
        JsonNode body = client.get("/boom/not_found").json().get("error");
        assertThat(body.get("code").asText()).isEqualTo("not_found");
        assertThat(body.get("retryable").asBoolean()).isFalse();
        assertThat(body.get("details").toString()).contains("bx-99");
    }

    @Test
    void aTransientErrorSaysItIsRetryableInTheBody() {
        JsonNode body = client.get("/boom/transient").json().get("error");
        assertThat(body.get("retryable").asBoolean()).isTrue();
        assertThat(body.get("code").asText()).isEqualTo("transient");
    }

    @Test
    void theStatusesAreWhatTheClientsExpect() {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("transient", 503);
        expected.put("chain", 503);
        expected.put("power", 503);
        expected.put("conflict", 409);
        expected.put("not_found", 404);
        expected.put("validation", 422);
        expected.put("unauthorized", 401);
        expected.put("forbidden", 403);
        expected.put("configuration", 500);
        expected.put("mismatch", 422);
        expected.forEach((kind, code) ->
                assertThat(client.get("/boom/" + kind).status()).as(kind).isEqualTo(code));
    }

    @Test
    void theHelperAgreesWithTheHandler() {
        assertThat(RaisingRoutes.RAISERS).isNotEmpty();
        for (Supplier<BenchError> raiser : RaisingRoutes.RAISERS.values()) {
            BenchError error = raiser.get();
            OptionalInt wait = Errors.retryAfterFor(error);
            assertThat(wait.isEmpty()).isNotEqualTo(error.retryable());
            assertThat(Errors.statusFor(error)).isGreaterThanOrEqualTo(400);
        }
    }

    @Test
    void aRealConflictFromARouteCarriesNoRetryAfter() {
        client.postJson("/boards/bx-14/lease", Map.of("holder", "team-a", "purpose", "capture"));
        Http.Answer second = client.postJson("/boards/bx-14/lease", Map.of("holder", "team-b", "purpose", "capture"));
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.header("retry-after")).isEmpty();
    }

    @Test
    void aRealNotFoundFromARouteCarriesTheTag() {
        JsonNode body = client.get("/boards/bx-99").json().get("error");
        assertThat(body.get("code").asText()).isEqualTo("not_found");
        assertThat(body.get("details").toString()).contains("bx-99");
    }

    @Test
    void aRouteThatWorkedHasNoErrorShape() {
        JsonNode body = client.get("/boards/bx-14").json();
        assertThat(body.has("error")).isFalse();
        assertThat(body.get("asset").asText()).isEqualTo("bx-14");
    }

    @Test
    void theLeaseRouteStillAnswersAfterAnError() {
        client.get("/boom/transient");
        try (BenchDatabase elsewhere = BenchDatabase.inMemory()) {
            elsewhere.run(unit -> {
                Inventory.addBoard(unit, BoardSetup.kintex("bx-20").build(), WHEN.minus(Duration.ofDays(1)));
                Leasing.acquire(unit, "bx-20", "team-a", "capture", WHEN);
            });
        }
        assertThat(client.get("/boards").status()).isEqualTo(200);
    }
}
