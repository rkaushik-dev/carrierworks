package io.github.rkaushikdev.carrierworks.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactKind;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiJobsTest {

    private static final Instant WHEN = Instant.parse("2026-01-20T09:00:00Z");
    private static final String DIGEST = "ab".repeat(32);
    private static final String OTHER = "cd".repeat(32);

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
            Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), WHEN);
            Inventory.addBoard(unit, BoardSetup.artix("bx-15").build(), WHEN);
            unit.artifacts().add(record(DIGEST), WHEN);
            unit.artifacts().add(record(OTHER), WHEN);
        });
        client = bench.http();
    }

    private static ArtifactRecord record(String digest) {
        return new ArtifactRecord(digest, "radar_frontend", "xc7k325t", 3_000_000, ArtifactKind.FULL)
                .withChannel("qual");
    }

    private Http.Answer submit() {
        return submit("bx-14", DIGEST, "jtag");
    }

    private Http.Answer submit(String board, String digest, String mode) {
        return client.postJson(
                "/jobs", Map.of("board", board, "digest", digest, "mode", mode, "requested_by", "team-a"));
    }

    private static void startJob(String jobId) {
        bench.database().run(unit -> unit.jobs().save(unit.jobs().get(jobId).start(WHEN)));
    }

    @Test
    void aModeIsTakenOnlyAsItIsSpelled() {
        Http.Answer response = submit("bx-14", DIGEST, "Flash_Update");
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.json().at("/error/message").asText()).isEqualTo("no such programming mode");
        assertThat(response.json().at("/error/details/given").asText()).isEqualTo("Flash_Update");
    }

    @Test
    void aJobGoesOnTheQueue() {
        Http.Answer response = submit();
        assertThat(response.status()).isEqualTo(201);
        JsonNode body = response.json();
        assertThat(body.get("id").asText()).isEqualTo("job-00001");
        assertThat(body.get("state").asText()).isEqualTo("queued");
        assertThat(body.get("verify").isBoolean()).isTrue();
        assertThat(body.get("verify").asBoolean()).isTrue();
    }

    @Test
    void jobNumbersCarryOn() {
        submit();
        assertThat(submit("bx-15", DIGEST, "jtag").json().get("id").asText()).isEqualTo("job-00002");
    }

    @Test
    void aJobForABoardNobodyHasIsA404() {
        assertThat(submit("bx-99", DIGEST, "jtag").status()).isEqualTo(404);
    }

    @Test
    void aJobForAnImageNobodyStoredIsA404() {
        assertThat(submit("bx-14", "ef".repeat(32), "jtag").status()).isEqualTo(404);
    }

    @Test
    void aModeNobodyHasIsRefused() {
        assertThat(submit("bx-14", DIGEST, "serial").status()).isEqualTo(422);
    }

    @Test
    void jobsComeBackByStateAndByImage() {
        submit();
        submit("bx-15", OTHER, "jtag");
        assertThat(client.get("/jobs?state=queued").json()).hasSize(2);
        JsonNode forImage = client.get("/jobs?digest=" + OTHER).json();
        assertThat(forImage).extracting(entry -> entry.get("board").asText()).containsExactly("bx-15");
    }

    @Test
    void oneJobComesBackOnItsOwn() {
        String jobId = submit().json().get("id").asText();
        assertThat(client.get("/jobs/" + jobId).json().get("board").asText()).isEqualTo("bx-14");
    }

    @Test
    void aJobNobodyAskedForIsA404() {
        assertThat(client.get("/jobs/job-09999").status()).isEqualTo(404);
    }

    @Test
    void theReadyListOffersOneJobPerBoard() {
        submit();
        submit();
        JsonNode ready = client.get("/jobs/ready").json();
        assertThat(ready).extracting(entry -> entry.get("id").asText()).containsExactly("job-00001");
    }

    @Test
    void theReadyListOffersJobsForDifferentBoards() {
        submit();
        submit("bx-15", DIGEST, "jtag");
        JsonNode ready = client.get("/jobs/ready").json();
        assertThat(ready).extracting(entry -> entry.get("board").asText()).containsExactly("bx-14", "bx-15");
    }

    @Test
    void aRunningJobKeepsItsBoardOutOfTheReadyList() {
        submit();
        submit();
        startJob("job-00001");
        assertThat(client.get("/jobs/ready").json()).isEmpty();
    }

    @Test
    void aSecondJobOnABoardIsRefusedWhileOneIsRunning() {
        submit();
        startJob("job-00001");
        Http.Answer response = submit();
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.json().get("error").get("details").get("job").asText())
                .isEqualTo("job-00001");
    }

    @Test
    void aCancelledJobLeavesTheQueue() {
        String jobId = submit().json().get("id").asText();
        JsonNode body =
                client.post("/jobs/" + jobId + "/cancel?reason=board+went+away").json();
        assertThat(body.get("state").asText()).isEqualTo("cancelled");
        assertThat(body.get("error").asText()).isEqualTo("board went away");
        assertThat(client.get("/jobs/ready").json()).isEmpty();
    }

    @Test
    void aCancelledJobCannotBeCancelledAgain() {
        String jobId = submit().json().get("id").asText();
        client.post("/jobs/" + jobId + "/cancel");
        assertThat(client.post("/jobs/" + jobId + "/cancel").status()).isEqualTo(422);
    }

    @Test
    void aBoardListsWhatHasBeenAskedOfIt() {
        submit();
        submit("bx-15", DIGEST, "jtag");
        JsonNode listed = client.get("/boards/bx-14/jobs").json();
        assertThat(listed).extracting(entry -> entry.get("id").asText()).containsExactly("job-00001");
        assertThat(client.get("/boards/bx-99/jobs").status()).isEqualTo(404);
    }

    @Test
    void aHeldJobIsNotOfferedUntilItsBackoffPasses() {
        submit();
        bench.database().run(unit -> {
            ProgramJob job = unit.jobs().get("job-00001");
            ProgramJob failed = job.start(WHEN).failed(WHEN, "cable", "chain", true);
            unit.jobs().save(failed, Optional.of(WHEN.plus(Duration.ofSeconds(30))));
        });
        assertThat(client.get("/jobs/ready").json()).isEmpty();
        bench.clock().advance(Duration.ofSeconds(31));
        JsonNode ready = client.get("/jobs/ready").json();
        assertThat(ready).extracting(entry -> entry.get("id").asText()).containsExactly("job-00001");
    }
}
