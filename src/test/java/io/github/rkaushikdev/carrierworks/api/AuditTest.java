package io.github.rkaushikdev.carrierworks.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.carrierworks.ApiKey;
import io.github.rkaushikdev.carrierworks.AuditEntry;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.Role;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.services.Audit;
import io.github.rkaushikdev.carrierworks.services.Inventory;
import io.github.rkaushikdev.carrierworks.services.Leasing;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditTest {

    private static final Instant WHEN = Instant.parse("2026-02-02T09:00:00Z");

    private static TestBench bench;
    private BenchDatabase database;
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
        database = bench.database();
        database.run(unit -> {
            Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), WHEN);
            Inventory.addBoard(unit, BoardSetup.artix("bx-15").build(), WHEN);
        });
        client = bench.http();
    }

    private List<AuditEntry> trail() {
        return database.apply(unit -> Audit.recent(unit));
    }

    private void take(String tag, String holder, String purpose) {
        client.postJson("/boards/" + tag + "/lease", Map.of("holder", holder, "purpose", purpose));
    }

    @Test
    void anEntryDescribesItself() {
        AuditEntry entry =
                new AuditEntry(WHEN, "Signal Processing", "lease-taken", "bx-14", "lease-00001 for capture", "");
        assertThat(entry.actor()).isEqualTo("signal-processing");
        assertThat(entry.describe())
                .isEqualTo("2026-02-02T09:00:00+00:00 signal-processing lease-taken bx-14: lease-00001 for capture");
    }

    /**
     * A time with no zone is refused. An entry keeps its moment as an {@link Instant}, which is
     * always UTC, so a zoneless time cannot be handed in; the test holds the type to that.
     */
    @Test
    void auditTimesAreInUtc() {
        assertThat(Arrays.stream(AuditEntry.class.getRecordComponents())
                        .filter(component -> component.getName().equals("at"))
                        .<Object>map(component -> component.getType()))
                .containsExactly(Instant.class);
    }

    @Test
    void takingABoardLeavesALine() {
        database.run(unit -> Leasing.acquire(unit, "bx-14", "team-a", "capture regression", WHEN));
        List<AuditEntry> trail = trail();
        assertThat(trail).extracting(AuditEntry::action).containsExactly("lease-taken");
        assertThat(trail.get(0).subject()).isEqualTo("bx-14");
        assertThat(trail.get(0).detail()).contains("capture regression");
    }

    @Test
    void givingItBackLeavesAnother() {
        Lease lease = database.apply(unit -> Leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN));
        database.run(unit -> Leasing.release(unit, lease.id(), WHEN.plus(Duration.ofMinutes(20)), "finished"));
        assertThat(trail()).extracting(AuditEntry::action).containsExactly("lease-released", "lease-taken");
    }

    @Test
    void aStateChangeIsRecordedWithBothStates() {
        database.run(
                unit -> Inventory.moveState(unit, "bx-14", BoardStatus.MAINTENANCE, WHEN, "header rework", "dana"));
        List<AuditEntry> trail = trail();
        assertThat(trail.get(0).action()).isEqualTo("board-state");
        assertThat(trail.get(0).detail()).contains("available to maintenance");
        assertThat(trail.get(0).actor()).isEqualTo("dana");
    }

    @Test
    void faultingABoardIsRecordedAsItsOwnAction() {
        database.run(unit -> Inventory.faultBoard(unit, "bx-14", WHEN, "chain read as all ones", "nightly"));
        List<AuditEntry> trail = trail();
        assertThat(trail.get(0).action()).isEqualTo("board-faulted");
        assertThat(trail.get(0).detail()).isEqualTo("chain read as all ones");
    }

    @Test
    void theTrailCanBeReadForOneBoard() {
        database.run(unit -> {
            Leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN);
            Leasing.acquire(unit, "bx-15", "team-b", "bring up", WHEN);
        });
        List<AuditEntry> forBoard = database.apply(unit -> Audit.forSubject(unit, "bx-15"));
        assertThat(forBoard).extracting(AuditEntry::subject).containsExactly("bx-15");
    }

    @Test
    void theTrailComesBackNewestFirst() {
        database.run(unit -> Leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN));
        database.run(unit -> Leasing.acquire(unit, "bx-15", "team-b", "bring up", WHEN.plus(Duration.ofMinutes(5))));
        assertThat(trail()).extracting(AuditEntry::subject).containsExactly("bx-15", "bx-14");
    }

    @Test
    void aLimitOfNothingIsABug() {
        assertThatThrownBy(() -> database.run(unit -> Audit.recent(unit, 0))).isInstanceOf(ValidationError.class);
    }

    @Test
    void aWindowReadsWhatHappenedInsideIt() {
        database.run(unit -> {
            Audit.record(unit, WHEN.minus(Duration.ofDays(2)), "board-state", "bx-14");
            Audit.record(unit, WHEN, "lease-taken", "bx-15");
        });
        List<AuditEntry> recent = database.apply(unit -> Audit.since(unit, WHEN, Duration.ofHours(1)));
        assertThat(recent).extracting(AuditEntry::subject).containsExactly("bx-15");
    }

    @Test
    void aWindowOfNoLengthIsABug() {
        assertThatThrownBy(() -> database.run(unit -> Audit.since(unit, WHEN, Duration.ZERO)))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aLineRecordsTheKeyThatDidIt() {
        ApiKey key = new ApiKey("abc", "nightly runner", Role.OPERATOR);
        database.run(unit -> Audit.record(unit, WHEN, "lease-taken", key, "bx-14", ""));
        List<AuditEntry> trail = trail();
        assertThat(trail.get(0).actor()).isEqualTo("nightly-runner");
        assertThat(trail.get(0).keyId()).isEqualTo("abc");
    }

    @Test
    void aLongDetailIsTrimmedRatherThanRefused() {
        database.run(unit -> Audit.record(unit, WHEN, "board-faulted", Audit.BENCH, "bx-14", "x".repeat(400)));
        assertThat(trail().get(0).detail()).hasSize(240);
    }

    @Test
    void theTrailIsReadableOverHttp() {
        take("bx-14", "team-a", "capture");
        JsonNode body = client.get("/audit").json();
        assertThat(body.get(0).get("action").asText()).isEqualTo("lease-taken");
        assertThat(body.get(0).get("subject").asText()).isEqualTo("bx-14");
    }

    @Test
    void theHttpTrailCanBeNarrowedToOneBoard() {
        take("bx-14", "team-a", "capture");
        take("bx-15", "team-b", "bring up");
        JsonNode body = client.get("/audit?subject=bx-15").json();
        assertThat(body).extracting(entry -> entry.get("subject").asText()).containsExactly("bx-15");
    }

    @Test
    void theHttpTrailTakesALimit() {
        take("bx-14", "team-a", "capture");
        take("bx-15", "team-b", "bring up");
        assertThat(client.get("/audit?limit=1").json()).hasSize(1);
        assertThat(client.get("/audit?limit=0").status()).isEqualTo(422);
    }
}
