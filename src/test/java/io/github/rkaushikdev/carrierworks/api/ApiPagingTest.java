package io.github.rkaushikdev.carrierworks.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.services.Leasing;
import io.github.rkaushikdev.carrierworks.services.Scheduling;
import io.github.rkaushikdev.carrierworks.services.Thermal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiPagingTest {

    private static final Instant WHEN = Instant.parse("2026-06-08T10:00:00Z");

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
            for (int index = 101; index < 113; index++) {
                Inventory.addBoard(unit, board(index), WHEN.minus(Duration.ofDays(1)));
            }
        });
        client = bench.http();
    }

    /** One of a dozen boards, named so that sorting them reads naturally. */
    private static Board board(int index) {
        int shelf = (index - 101) / 6 + 1;
        int slot = (index - 101) % 6 + 1;
        return BoardSetup.kintex("bx-" + index)
                .location(String.format(Locale.ROOT, "rack-5/shelf-%02d/slot-%d", shelf, slot))
                .serial(String.format(Locale.ROOT, "2103080%05d", index))
                .outlet(index % 24 + 1)
                .pduName("pdu-" + index / 24)
                .build();
    }

    private static List<String> column(JsonNode rows, String field) {
        List<String> values = new ArrayList<>();
        rows.forEach(row -> values.add(row.get(field).asText()));
        return values;
    }

    private static void thirtyReadings() {
        bench.database().run(unit -> {
            for (int index = 0; index < 30; index++) {
                Thermal.record(unit, "bx-101", WHEN.minus(Duration.ofSeconds(20L * index)), 60_000 + index);
            }
        });
    }

    @Test
    void aPageSlicesWhatItIsGiven() {
        assertThat(new Page(2, 1).slice(List.of(1, 2, 3, 4))).containsExactly(2, 3);
    }

    @Test
    void aPagePastTheEndIsEmpty() {
        assertThat(new Page(5, 99).slice(List.of(1, 2, 3))).isEmpty();
    }

    @Test
    void theDefaultPageIsTheWholeShortListing() {
        assertThat(client.get("/boards").json()).hasSize(12);
    }

    @Test
    void aListingCanBeCutDown() {
        assertThat(column(client.get("/boards?limit=5").json(), "asset"))
                .containsExactly("bx-101", "bx-102", "bx-103", "bx-104", "bx-105");
    }

    @Test
    void aListingCanBeSkippedInto() {
        assertThat(column(client.get("/boards?limit=3&offset=9").json(), "asset"))
                .containsExactly("bx-110", "bx-111", "bx-112");
    }

    @Test
    void aPagePastTheEndAnswersWithNothing() {
        JsonNode body = client.get("/boards?offset=50").json();
        assertThat(body.isArray()).isTrue();
        assertThat(body).isEmpty();
    }

    @Test
    void aLimitOfZeroIsRefused() {
        assertThat(client.get("/boards?limit=0").status()).isEqualTo(422);
    }

    @Test
    void aLimitOverTheCeilingIsRefused() {
        assertThat(client.get("/boards?limit=" + (Page.MAX + 1)).status()).isEqualTo(422);
    }

    @Test
    void aNegativeOffsetIsRefused() {
        assertThat(client.get("/boards?offset=-1").status()).isEqualTo(422);
    }

    @Test
    void theFiltersStillApplyBeforeThePage() {
        bench.database().run(unit -> Leasing.acquire(unit, "bx-101", "team-a", "capture", WHEN));
        JsonNode body = client.get("/boards?available=true&limit=3").json();
        assertThat(column(body, "asset")).doesNotContain("bx-101").hasSize(3);
    }

    @Test
    void leasesArePaged() {
        bench.database().run(unit -> {
            for (int index = 101; index < 105; index++) {
                Leasing.acquire(unit, "bx-" + index, "team-a", "capture", WHEN);
            }
        });
        assertThat(client.get("/leases?limit=2").json()).hasSize(2);
        assertThat(client.get("/leases?limit=2&offset=3").json()).hasSize(1);
    }

    @Test
    void theQueueIsPaged() {
        bench.database().run(unit -> {
            for (int index = 0; index < 5; index++) {
                Scheduling.submit(unit, "team-" + index, "capture", "", WHEN);
            }
        });
        assertThat(column(client.get("/requests?limit=2&offset=2").json(), "holder"))
                .containsExactly("team-2", "team-3");
    }

    @Test
    void thermalHistoryIsPaged() {
        thirtyReadings();
        JsonNode body = client.get("/boards/bx-101/thermal?limit=10").json();
        assertThat(body).hasSize(10);
        assertThat(body.get(0).get("millidegrees").asInt()).isEqualTo(60_029);
    }

    @Test
    void thermalHistoryCanBeSkippedInto() {
        thirtyReadings();
        assertThat(column(client.get("/boards/bx-101/thermal?limit=5&offset=25").json(), "millidegrees"))
                .containsExactly("60004", "60003", "60002", "60001", "60000");
    }
}
