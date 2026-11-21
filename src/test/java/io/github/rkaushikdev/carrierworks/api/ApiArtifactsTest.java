package io.github.rkaushikdev.carrierworks.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.bitstream.Images;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;

class ApiArtifactsTest {

    private static final Instant WHEN = Instant.parse("2026-01-16T08:00:00Z");

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

    private Http.Answer upload() {
        return upload(Images.image(), "dev");
    }

    private Http.Answer upload(Images.Image image, String channel) {
        return client.postBytes("/artifacts?channel=" + channel, image.bitfile(), MediaType.APPLICATION_OCTET_STREAM);
    }

    private Http.Answer recordBuild(String digest, Map<String, Object> changes) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("build_id", "nightly-1841");
        payload.put("source_revision", "9f2c11a");
        payload.put("built_at", Times.iso(WHEN));
        payload.put("constraints_digest", "c0ffee");
        payload.putAll(changes);
        return client.postJson("/artifacts/" + digest + "/builds", payload);
    }

    private String uploaded() {
        return upload().json().get("digest").asText();
    }

    private static String blockers(Http.Answer response) {
        return response.json().get("error").get("details").get("blockers").asText();
    }

    @Test
    void anUploadStoresTheImageAndRecordsIt() {
        Http.Answer response = upload();
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("design").asText()).isEqualTo("radar_frontend");
        assertThat(body.get("device").asText()).isEqualTo("xc7k325t");
        assertThat(body.get("kind").asText()).isEqualTo("full");
        assertThat(body.get("channel").asText()).isEqualTo("dev");
        assertThat(body.get("digest").asText()).hasSize(64);
    }

    @Test
    void uploadingTheSameImageTwiceIsOneRecord() {
        JsonNode first = upload().json();
        JsonNode second = upload().json();
        assertThat(first.get("digest").asText()).isEqualTo(second.get("digest").asText());
        assertThat(client.get("/artifacts").json()).hasSize(1);
    }

    @Test
    void aPartialImageIsRecordedAsPartial() {
        JsonNode body = upload(Images.image().start(false), "dev").json();
        assertThat(body.get("kind").asText()).isEqualTo("partial");
    }

    @Test
    void somethingThatIsNotABitFileIsRefused() {
        Http.Answer response = client.postBytes(
                "/artifacts", "nonsense".getBytes(StandardCharsets.US_ASCII), MediaType.APPLICATION_OCTET_STREAM);
        assertThat(response.status()).isEqualTo(422);
    }

    @Test
    void anImageComesBackOnItsOwn() {
        String digest = uploaded();
        assertThat(client.get("/artifacts/" + digest).json().get("digest").asText())
                .isEqualTo(digest);
    }

    @Test
    void anImageNobodyStoredIsA404() {
        assertThat(client.get("/artifacts/" + "ab".repeat(32)).status()).isEqualTo(404);
    }

    @Test
    void imagesAreListedByDesignAndByChannel() {
        upload();
        upload(Images.image().design("beamformer").frames(20), "qual");
        JsonNode byDesign = client.get("/artifacts?design=beamformer").json();
        assertThat(byDesign).extracting(entry -> entry.get("design").asText()).containsExactly("beamformer");
        JsonNode byChannel = client.get("/artifacts?channel=qual").json();
        assertThat(byChannel).extracting(entry -> entry.get("design").asText()).containsExactly("beamformer");
    }

    @Test
    void anImageWithNoEvidenceCannotBePromoted() {
        String digest = uploaded();
        Http.Answer response = client.postJson("/artifacts/" + digest + "/promote", Map.of("channel", "qual"));
        assertThat(response.status()).isEqualTo(409);
        assertThat(blockers(response)).contains("timing");
    }

    @Test
    void aBuildCanBeRecordedAgainstAnImage() {
        String digest = uploaded();
        Http.Answer response = recordBuild(digest, Map.of());
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("reproducible").asBoolean()).isTrue();
    }

    @Test
    void anImageWithACleanBuildAndTimingGoesToQual() {
        String digest = uploaded();
        recordBuild(digest, Map.of());
        Http.Answer response =
                client.postJson("/artifacts/" + digest + "/promote", Map.of("channel", "qual", "timing_closed", true));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("channel").asText()).isEqualTo("qual");
        assertThat(client.get("/artifacts/" + digest).json().get("channel").asText())
                .isEqualTo("qual");
    }

    @Test
    void anImageBuiltFromADirtyTreeStaysInDev() {
        String digest = uploaded();
        recordBuild(digest, Map.of("dirty", true));
        Http.Answer response =
                client.postJson("/artifacts/" + digest + "/promote", Map.of("channel", "qual", "timing_closed", true));
        assertThat(response.status()).isEqualTo(409);
        assertThat(blockers(response)).contains("reproduced");
    }

    @Test
    void aDesignThatMissedTimingStaysWhereItIs() {
        String digest = uploaded();
        Http.Answer response =
                client.postJson("/artifacts/" + digest + "/promote", Map.of("channel", "qual", "timing_closed", false));
        assertThat(response.status()).isEqualTo(409);
        assertThat(client.get("/artifacts/" + digest).json().get("channel").asText())
                .isEqualTo("dev");
    }

    @Test
    void promotionStillGoesOneChannelAtATime() {
        String digest = uploaded();
        recordBuild(digest, Map.of());
        Http.Answer response = client.postJson(
                "/artifacts/" + digest + "/promote",
                Map.of("channel", "golden", "timing_closed", true, "approvals", List.of("kmoyer")));
        assertThat(response.status()).isEqualTo(409);
        assertThat(blockers(response)).contains("one channel at a time");
    }

    @Test
    void theLoadCountsStartAtNothing() {
        String digest = uploaded();
        JsonNode body = client.get("/artifacts/" + digest + "/loads").json();
        assertThat(Http.JSON.convertValue(body, new TypeReference<Map<String, Object>>() {}))
                .isEqualTo(Map.of("passed", 0, "failed", 0));
    }

    @Test
    void theLoadCountsAreA404ForAnImageNobodyHas() {
        assertThat(client.get("/artifacts/" + "cd".repeat(32) + "/loads").status())
                .isEqualTo(404);
    }
}
