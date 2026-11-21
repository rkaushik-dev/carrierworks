package io.github.rkaushikdev.carrierworks.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.bitstream.Images;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;

class ApiReportsTest {

    private static final Instant WHEN = Instant.parse("2026-02-08T07:00:00Z");

    @TempDir
    static Path tmp;

    private static TestBench bench;
    private Http client;

    @BeforeAll
    static void start() {
        bench = TestBench.start(
                BenchSettings.builder().artifactRoot(tmp.resolve("artifacts")).build(), WHEN);
    }

    @AfterAll
    static void stop() {
        bench.close();
    }

    @BeforeEach
    void fresh() {
        bench.reset(WHEN);
        TestBench.empty(tmp.resolve("artifacts"));
        client = bench.http();
    }

    private String upload() {
        return upload(16);
    }

    private String upload(int frames) {
        Http.Answer response =
                client.postBytes("/artifacts", Images.bitfile(frames), MediaType.APPLICATION_OCTET_STREAM);
        return response.json().get("digest").asText();
    }

    private Http.Answer ingest(String digest) {
        return ingest(digest, Images.TIMING);
    }

    private Http.Answer ingest(String digest, String timing) {
        return client.postJson(
                "/artifacts/" + digest + "/reports", Map.of("timing", timing, "utilization", Images.UTILIZATION));
    }

    private Http.Answer build(String digest) {
        return client.postJson(
                "/artifacts/" + digest + "/builds",
                Map.of(
                        "build_id",
                        "nightly-" + digest.substring(0, 4),
                        "source_revision",
                        "9f2c11a",
                        "built_at",
                        Times.iso(WHEN),
                        "constraints_digest",
                        "c0ffee"));
    }

    private JsonNode baseline() {
        return client.get("/designs/radar_frontend/baseline").json();
    }

    @Test
    void reportsAreReadInAndStored() {
        String digest = upload();
        Http.Answer response = ingest(digest);
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("closed").asBoolean()).isTrue();
        assertThat(body.get("wns_ps").asLong()).isEqualTo(412);
        assertThat(body.get("luts").asInt()).isEqualTo(45123);
    }

    @Test
    void theStoredReportReadsBack() {
        String digest = upload();
        ingest(digest);
        JsonNode body = client.get("/artifacts/" + digest + "/reports").json();
        assertThat(body.get("whs_ps").asLong()).isEqualTo(137);
        assertThat(body.get("registers").asInt()).isEqualTo(61234);
    }

    @Test
    void anImageWithNoReportsReadIsA404() {
        String digest = upload();
        assertThat(client.get("/artifacts/" + digest + "/reports").status()).isEqualTo(404);
    }

    @Test
    void reportsForAnImageNobodyStoredIsA404() {
        assertThat(ingest("ab".repeat(32)).status()).isEqualTo(404);
    }

    @Test
    void readingTheReportsTwiceIsRefused() {
        String digest = upload();
        ingest(digest);
        assertThat(ingest(digest).status()).isEqualTo(422);
    }

    @Test
    void aReportThatIsNotOneIsRefused() {
        String digest = upload();
        assertThat(ingest(digest, "nothing to see here").status()).isEqualTo(422);
    }

    @Test
    void theFirstClosingBuildBecomesTheBaseline() {
        String digest = upload();
        ingest(digest);
        assertThat(baseline().get("digest").asText()).isEqualTo(digest);
    }

    @Test
    void aBuildThatMissedTimingDoesNotBecomeTheBaseline() {
        String digest = upload();
        ingest(digest, Images.FAILING_TIMING);
        assertThat(baseline().isNull()).isTrue();
    }

    @Test
    void aLaterBuildDoesNotMoveTheBaseline() {
        String first = upload();
        ingest(first);
        String second = upload(20);
        ingest(second, Images.TIGHT_TIMING);
        assertThat(baseline().get("digest").asText()).isEqualTo(first);
    }

    @Test
    void theBaselineCanBePinned() {
        String first = upload();
        ingest(first);
        String second = upload(20);
        ingest(second, Images.TIGHT_TIMING);
        JsonNode body = client.postJson("/designs/radar_frontend/baseline", Map.of("digest", second))
                .json();
        assertThat(body.get("digest").asText()).isEqualTo(second);
        assertThat(baseline().get("digest").asText()).isEqualTo(second);
    }

    @Test
    void aTighterBuildIsReportedAsARegression() {
        String first = upload();
        ingest(first);
        String second = upload(20);
        ingest(second, Images.TIGHT_TIMING);
        JsonNode body = client.get("/artifacts/" + second + "/regressions").json();
        assertThat(body.get("clean").asBoolean()).isFalse();
        assertThat(body.get("regressions")).extracting(JsonNode::asText).anySatisfy(line -> assertThat(line)
                .contains("slack fell"));
    }

    @Test
    void theBaselineDoesNotRegressAgainstItself() {
        String digest = upload();
        ingest(digest);
        assertThat(client.get("/artifacts/" + digest + "/regressions")
                        .json()
                        .get("clean")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void promotionReadsTheStoredTimingRatherThanTheCaller() {
        String digest = upload();
        build(digest);
        ingest(digest);
        Http.Answer response = client.postJson("/artifacts/" + digest + "/promote", Map.of("channel", "qual"));
        assertThat(response.status()).isEqualTo(200);
        assertThat(client.get("/artifacts/" + digest).json().get("channel").asText())
                .isEqualTo("qual");
    }

    @Test
    void aCallerCannotClaimTimingAReportContradicts() {
        String digest = upload();
        build(digest);
        ingest(digest, Images.FAILING_TIMING);
        Http.Answer response =
                client.postJson("/artifacts/" + digest + "/promote", Map.of("channel", "qual", "timing_closed", true));
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("error").get("details").get("blockers").asText())
                .contains("did not close timing");
    }

    @Test
    void regressionsForAnImageWithNoReportAreA404() {
        String digest = upload();
        assertThat(client.get("/artifacts/" + digest + "/regressions").status()).isEqualTo(404);
    }
}
