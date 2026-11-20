package io.github.rkaushikdev.carrierworks.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.artifacts.ArtifactKind;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import io.github.rkaushikdev.carrierworks.catalog.CardSpec;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.jobs.JobMode;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MappersTest {

    private static final Instant NOW = Instant.parse("2025-12-03T11:00:00Z");
    private static final String DIGEST = "ab".repeat(32);

    private BenchDatabase database;
    private Engine engine;

    @BeforeEach
    void open() {
        database = BenchDatabase.inMemory();
        engine = database.engine();
    }

    @AfterEach
    void close() {
        database.close();
    }

    private static Lease lease() {
        return new Lease("lease-00001", "bx-14", "team-a", "capture", NOW, NOW.plus(Duration.ofHours(1)));
    }

    private static ProgramJob job() {
        return ProgramJob.builder("job-00001", "bx-14", DIGEST, JobMode.JTAG, "team-a", NOW)
                .build();
    }

    private static ArtifactRecord record() {
        return new ArtifactRecord(
                DIGEST,
                "radar_frontend",
                "xc7k325t",
                3_000_000,
                ArtifactKind.FULL,
                "qual",
                "radar.bit",
                "2024.1",
                "",
                List.of("keep", "nightly"));
    }

    private Board stored(Board original) {
        engine.sessionScope(session -> session.persist(Mappers.boardToRow(original)));
        Board[] found = new Board[1];
        engine.sessionScope(session -> found[0] = Mappers.boardFromRow(session.find(BoardRow.class, original.tag())));
        return found[0];
    }

    @Test
    void aBoardRoundTripsInMemory() {
        Board original = BoardSetup.kintex();
        assertThat(Mappers.boardFromRow(Mappers.boardToRow(original)).describe())
                .isEqualTo(original.describe());
    }

    @Test
    void aBoardRoundTripsThroughTheDatabase() {
        Board original = BoardSetup.kintex();
        Board found = stored(original);
        assertThat(found.describe()).isEqualTo(original.describe());
        assertThat(found.adapter()).isPresent();
        assertThat(found.adapter().get().serial()).isEqualTo("210308AB1234");
        assertThat(found.pdu().orElseThrow()).hasToString("pdu-a/outlet-07");
    }

    @Test
    void aBareBoardRoundTripsWithoutACableOrAnOutlet() {
        Board found = stored(BoardSetup.kintex("bx-14").bare().slots().build());
        assertThat(found.adapter()).isEmpty();
        assertThat(found.pdu()).isEmpty();
        assertThat(found.slots()).isEmpty();
    }

    @Test
    void theFittedCardsSurviveTheTrip() {
        Board found = stored(BoardSetup.kintex());
        assertThat(found.fittedCards()).extracting(CardSpec::name).containsExactly("fmc-adc-4ch-14b");
        assertThat(found.slot("fmc1").isEmpty()).isTrue();
    }

    @Test
    void aStateRoundTripsAndKeepsItsZone() {
        BoardState original = new BoardState(BoardStatus.FAULTED, NOW, "chain", "nightly");
        engine.sessionScope(session -> {
            session.persist(Mappers.boardToRow(BoardSetup.kintex()));
            session.persist(Mappers.stateToRow("bx-14", original));
        });
        BoardState[] found = new BoardState[1];
        engine.sessionScope(session -> found[0] = Mappers.stateFromRow(
                session.find(BoardRow.class, "bx-14").getState().orElseThrow()));
        assertThat(found[0]).isEqualTo(original);
        assertThat(found[0].since()).isEqualTo(NOW);
    }

    @Test
    void theAvailableFlagIsWrittenForTheQueriesThatReadIt() {
        engine.sessionScope(session -> {
            session.persist(Mappers.boardToRow(BoardSetup.kintex()));
            session.persist(Mappers.stateToRow("bx-14", new BoardState(BoardStatus.AVAILABLE, NOW)));
        });
        engine.sessionScope(session -> assertThat(session.find(BoardRow.class, "bx-14")
                        .getState()
                        .orElseThrow()
                        .isAvailable())
                .isTrue());
    }

    @Test
    void aLeaseRoundTrips() {
        engine.sessionScope(session -> {
            session.persist(Mappers.boardToRow(BoardSetup.kintex()));
            session.persist(Mappers.leaseToRow(lease()));
        });
        Lease[] found = new Lease[1];
        engine.sessionScope(session -> found[0] = Mappers.leaseFromRow(session.find(LeaseRow.class, "lease-00001")));
        assertThat(found[0]).isEqualTo(lease());
    }

    @Test
    void aReleasedLeaseKeepsItsReason() {
        Lease closed = lease().release(NOW.plus(Duration.ofMinutes(20)), "finished");
        engine.sessionScope(session -> {
            session.persist(Mappers.boardToRow(BoardSetup.kintex()));
            session.persist(Mappers.leaseToRow(closed));
        });
        Lease[] found = new Lease[1];
        engine.sessionScope(session -> found[0] = Mappers.leaseFromRow(session.find(LeaseRow.class, "lease-00001")));
        assertThat(found[0].released()).isTrue();
        assertThat(found[0].releaseReason()).isEqualTo("finished");
        assertThat(found[0].releasedAt()).contains(NOW.plus(Duration.ofMinutes(20)));
    }

    @Test
    void aJobRoundTrips() {
        engine.sessionScope(session -> {
            session.persist(Mappers.boardToRow(BoardSetup.kintex()));
            session.persist(Mappers.jobToRow(job()));
        });
        ProgramJob[] found = new ProgramJob[1];
        engine.sessionScope(session -> found[0] = Mappers.jobFromRow(session.find(JobRow.class, "job-00001")));
        assertThat(found[0]).isEqualTo(job());
    }

    @Test
    void aFinishedJobKeepsItsErrorAndItsTimes() {
        ProgramJob failed = job().start(NOW).failed(NOW.plus(Duration.ofMinutes(1)), "cable", "chain");
        engine.sessionScope(session -> {
            session.persist(Mappers.boardToRow(BoardSetup.kintex()));
            session.persist(Mappers.jobToRow(failed));
        });
        ProgramJob[] found = new ProgramJob[1];
        engine.sessionScope(session -> found[0] = Mappers.jobFromRow(session.find(JobRow.class, "job-00001")));
        assertThat(found[0].error()).isEqualTo("cable");
        assertThat(found[0].errorCode()).isEqualTo("chain");
        assertThat(found[0].duration()).contains(Duration.ofMinutes(1));
    }

    @Test
    void aHeldJobKeepsTheTimeItIsHeldUntil() {
        Instant hold = NOW.plus(Duration.ofSeconds(45));
        engine.sessionScope(session -> {
            session.persist(Mappers.boardToRow(BoardSetup.kintex()));
            session.persist(Mappers.jobToRow(job(), Optional.of(hold)));
        });
        engine.sessionScope(
                session -> assertThat(session.find(JobRow.class, "job-00001").getNotBefore())
                        .isEqualTo(hold));
    }

    @Test
    void anArtifactRoundTripsWithItsTags() {
        engine.sessionScope(session -> session.persist(Mappers.artifactToRow(record(), NOW)));
        ArtifactRecord[] found = new ArtifactRecord[1];
        engine.sessionScope(session -> found[0] = Mappers.artifactFromRow(session.find(ArtifactRow.class, DIGEST)));
        assertThat(found[0].tags()).containsExactly("keep", "nightly");
        assertThat(found[0].channel()).isEqualTo("qual");
        assertThat(found[0].toolVersion()).isEqualTo("2024.1");
    }

    @Test
    void anArtifactWithNoTagsComesBackWithNone() {
        engine.sessionScope(session -> session.persist(Mappers.artifactToRow(record().tagged(), NOW)));
        ArtifactRecord[] found = new ArtifactRecord[1];
        engine.sessionScope(session -> found[0] = Mappers.artifactFromRow(session.find(ArtifactRow.class, DIGEST)));
        assertThat(found[0].tags()).containsExactly("keep", "nightly");
    }

    static Stream<Arguments> aNaiveTimestampIsRefusedOnTheWayIn() {
        return Stream.of(Arguments.of(BoardState.class, "since"), Arguments.of(Lease.class, "takenAt"));
    }

    /**
     * A timestamp with no zone never reaches a row. The records the mappers read keep their
     * moments as instants, which are always UTC, so there is no zoneless value to refuse; the
     * test holds the component types to that.
     */
    @ParameterizedTest
    @MethodSource
    void aNaiveTimestampIsRefusedOnTheWayIn(Class<? extends Record> type, String component) {
        assertThat(Arrays.stream(type.getRecordComponents())
                        .filter(entry -> entry.getName().equals(component))
                        .<Object>map(entry -> entry.getType()))
                .containsExactly(Instant.class);
    }
}
