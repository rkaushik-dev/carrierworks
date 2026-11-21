package io.github.rkaushikdev.carrierworks.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.BenchError;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.ChainError;
import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.Conflict;
import io.github.rkaushikdev.carrierworks.DeviceMismatch;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.testroutes.FailingRoutes;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.context.WebApplicationContext;

class ApiAppTest {

    private static final Instant WHEN = Instant.parse("2026-01-20T09:00:00Z");

    private static TestBench bench;
    private Http client;

    @BeforeAll
    static void start() {
        bench = TestBench.start(BenchSettings.builder().site("lab-b").build(), WHEN, FailingRoutes.class);
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

    @Test
    void healthSaysWhichSiteItIs() {
        Http.Answer response = client.get("/health");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("site").asText()).isEqualTo("lab-b");
        assertThat(response.json().get("status").asText()).isEqualTo("ok");
    }

    @Test
    void theSettingsRouteShowsWhatIsInForce() {
        JsonNode body = client.get("/settings").json();
        assertThat(body.get("default_lease_minutes").asInt()).isEqualTo(60);
        assertThat(body.get("require_timing_closure").asBoolean()).isTrue();
    }

    @Test
    void nothingSecretIsInTheSettingsRoute() {
        JsonNode body = client.get("/settings").json();
        assertThat(body.has("database_url")).isFalse();
        assertThat(body.has("api_key_header")).isFalse();
    }

    static Stream<Arguments> everyErrorHasItsStatus() {
        return Stream.of(
                Arguments.of(new NotFound("board", "bx-99"), 404),
                Arguments.of(new Conflict("that board is already leased"), 409),
                Arguments.of(new ValidationError("bad asset tag"), 422),
                Arguments.of(new DeviceMismatch("wrong device", "a", "b"), 422),
                Arguments.of(new ChainError("the chain did not answer"), 503),
                Arguments.of(new ConfigurationError("two boards claim one slot"), 500),
                Arguments.of(new BenchError("something else"), 400));
    }

    @ParameterizedTest
    @MethodSource
    void everyErrorHasItsStatus(BenchError error, int expected) {
        assertThat(Errors.statusFor(error)).isEqualTo(expected);
    }

    @Test
    void anErrorComesBackInTheOneShape() {
        Http.Answer response = client.get("/boom");
        assertThat(response.status()).isEqualTo(404);
        JsonNode body = response.json().get("error");
        assertThat(body.get("code").asText()).isEqualTo("not_found");
        assertThat(Http.JSON.convertValue(body.get("details"), new TypeReference<Map<String, Object>>() {}))
                .isEqualTo(Map.of("kind", "board", "key", "bx-99"));
        assertThat(body.get("retryable").asBoolean()).isFalse();
    }

    @Test
    void aTransientErrorSaysItCanBeRetried() {
        Http.Answer response = client.get("/flaky");
        assertThat(response.status()).isEqualTo(503);
        assertThat(response.json().get("error").get("retryable").asBoolean()).isTrue();
        assertThat(response.json().get("error").get("details").get("board").asText())
                .isEqualTo("bx-14");
    }

    @Test
    void theAppCarriesItsDatabaseAndSettings() {
        assertThat(bench.context()).isInstanceOf(WebApplicationContext.class);
        assertThat(bench.context().getBean(BenchSettings.class).site()).isEqualTo("lab-b");
        List<Board> boards = bench.context().getBean(BenchDatabase.class).apply(unit -> unit.boards()
                .all());
        assertThat(boards).isEmpty();
    }

    @Test
    void theAppBuildsWithDefaults() {
        try (TestBench defaults = TestBench.start(BenchSettings.defaults(), WHEN)) {
            assertThat(defaults.context().getBean(BenchSettings.class).site()).isEqualTo("lab-a");
            assertThat(defaults.http().get("/health").status()).isEqualTo(200);
        }
    }

    @Test
    void aRouteThatDoesNotExistIsAPlain404() {
        assertThat(client.get("/nothing-here").status()).isEqualTo(404);
    }

    @Test
    void theServiceDescribesItselfAsAnOpenApiDocument() {
        Http.Answer response = client.get("/openapi.json");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("info").get("title").asText()).isEqualTo(BenchApplication.TITLE);
        assertThat(response.json().get("paths").has("/health")).isTrue();
    }
}
