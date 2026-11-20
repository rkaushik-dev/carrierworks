package io.github.rkaushikdev.carrierworks.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactKind;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.jobs.JobMode;
import io.github.rkaushikdev.carrierworks.jobs.JobState;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RepositoriesTest {

    private static final Instant NOW = Instant.parse("2025-12-08T09:00:00Z");
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

    private static BoardState available() {
        return new BoardState(BoardStatus.AVAILABLE, NOW);
    }

    private static Lease lease() {
        return lease("lease-00001", "bx-14", "team-a", 1);
    }

    private static Lease lease(String id, String board, String holder, int hours) {
        return new Lease(id, board, holder, "capture", NOW, NOW.plus(Duration.ofHours(hours)));
    }

    private static ProgramJob job() {
        return job("job-00001", DIGEST);
    }

    private static ProgramJob job(String id, String digest) {
        return ProgramJob.builder(id, "bx-14", digest, JobMode.JTAG, "team-a", NOW)
                .build();
    }

    private static ArtifactRecord record() {
        return record(DIGEST, "dev", "radar_frontend");
    }

    private static ArtifactRecord record(String digest, String channel, String design) {
        return new ArtifactRecord(digest, design, "xc7k325t", 3_000_000, ArtifactKind.FULL).withChannel(channel);
    }

    @Test
    void aBoardGoesInAndComesBack() {
        engine.sessionScope(session -> new BoardRepository(session).add(BoardSetup.kintex(), available()));
        engine.sessionScope(session -> {
            Board found = new BoardRepository(session).get("BX-0014");
            assertThat(found.model()).isEqualTo("rx-carrier-b");
            assertThat(new BoardRepository(session).state("bx-14").usable()).isTrue();
        });
    }

    @Test
    void aBoardNobodyAddedIsANotFound() {
        assertThatThrownBy(() -> engine.sessionScope(session -> new BoardRepository(session).get("bx-99")))
                .isInstanceOf(NotFound.class);
    }

    @Test
    void onlyAvailableBoardsComeBackFromTheAvailableQuery() {
        engine.sessionScope(session -> {
            BoardRepository repo = new BoardRepository(session);
            repo.add(BoardSetup.kintex(), available());
            repo.add(BoardSetup.artix(), new BoardState(BoardStatus.FAULTED, NOW, "chain"));
        });
        engine.sessionScope(session -> {
            assertThat(new BoardRepository(session).available())
                    .extracting(Board::tag)
                    .containsExactly("bx-14");
            assertThat(new BoardRepository(session).all()).hasSize(2);
        });
    }

    @Test
    void aStateChangeIsWrittenBack() {
        engine.sessionScope(session -> new BoardRepository(session).add(BoardSetup.kintex(), available()));
        engine.sessionScope(session -> new BoardRepository(session)
                .saveState("bx-14", new BoardState(BoardStatus.LEASED, NOW.plus(Duration.ofMinutes(5)))));
        engine.sessionScope(session -> {
            assertThat(new BoardRepository(session).state("bx-14").state()).isEqualTo(BoardStatus.LEASED);
            assertThat(new BoardRepository(session).available()).isEmpty();
        });
    }

    @Test
    void aChangedBoardIsWrittenBackWithItsSlots() {
        engine.sessionScope(session -> new BoardRepository(session).add(BoardSetup.kintex(), available()));
        engine.sessionScope(session -> {
            BoardRepository repo = new BoardRepository(session);
            repo.replace(repo.get("bx-14").withCard("fmc1", "fmc-adc-2ch-16b"));
        });
        engine.sessionScope(session -> {
            Board found = new BoardRepository(session).get("bx-14");
            assertThat(found.fittedCards()).hasSize(2);
            assertThat(found.slot("fmc1").fitted()).contains("fmc-adc-2ch-16b");
        });
    }

    @Test
    void boardsComeBackByRack() {
        engine.sessionScope(session -> {
            BoardRepository repo = new BoardRepository(session);
            repo.add(
                    BoardSetup.kintex("bx-14")
                            .location("rack-2/shelf-04/slot-3")
                            .build(),
                    available());
            repo.add(
                    BoardSetup.artix("bx-15").location("rack-4/shelf-01/slot-1").build(), available());
        });
        engine.sessionScope(session -> assertThat(new BoardRepository(session).inRack("rack-4"))
                .extracting(Board::tag)
                .containsExactly("bx-15"));
    }

    @Test
    void aRemovedBoardIsGone() {
        engine.sessionScope(session -> new BoardRepository(session).add(BoardSetup.kintex(), available()));
        engine.sessionScope(session -> new BoardRepository(session).remove("bx-14"));
        engine.sessionScope(
                session -> assertThat(new BoardRepository(session).all()).isEmpty());
    }

    @Test
    void aLeaseIsFoundForItsBoard() {
        engine.sessionScope(session -> {
            new BoardRepository(session).add(BoardSetup.kintex(), available());
            new LeaseRepository(session).add(lease());
        });
        engine.sessionScope(session -> {
            Optional<Lease> found = new LeaseRepository(session).forBoard("bx-14", NOW.plus(Duration.ofMinutes(1)));
            assertThat(found).isPresent();
            assertThat(found.get().holder()).isEqualTo("team-a");
        });
    }

    @Test
    void anExpiredLeaseDoesNotHoldABoard() {
        engine.sessionScope(session -> {
            new BoardRepository(session).add(BoardSetup.kintex(), available());
            new LeaseRepository(session).add(lease());
        });
        engine.sessionScope(session -> {
            assertThat(new LeaseRepository(session).forBoard("bx-14", NOW.plus(Duration.ofHours(2))))
                    .isEmpty();
            assertThat(new LeaseRepository(session).active(NOW.plus(Duration.ofHours(2))))
                    .isEmpty();
        });
    }

    @Test
    void aReleasedLeaseStopsHoldingItsBoard() {
        engine.sessionScope(session -> {
            new BoardRepository(session).add(BoardSetup.kintex(), available());
            new LeaseRepository(session).add(lease());
        });
        engine.sessionScope(session -> {
            LeaseRepository repo = new LeaseRepository(session);
            repo.save(repo.get("lease-00001").release(NOW.plus(Duration.ofMinutes(10)), "finished"));
        });
        engine.sessionScope(session -> {
            assertThat(new LeaseRepository(session).forBoard("bx-14", NOW.plus(Duration.ofMinutes(11))))
                    .isEmpty();
            assertThat(new LeaseRepository(session).history("bx-14")).hasSize(1);
        });
    }

    @Test
    void whatAHolderHas() {
        engine.sessionScope(session -> {
            BoardRepository repo = new BoardRepository(session);
            repo.add(BoardSetup.kintex(), available());
            repo.add(BoardSetup.artix(), available());
            new LeaseRepository(session).add(lease());
            new LeaseRepository(session).add(lease("lease-00002", "bx-15", "team-b", 1));
        });
        engine.sessionScope(
                session -> assertThat(new LeaseRepository(session).heldBy("team-a", NOW.plus(Duration.ofMinutes(1))))
                        .extracting(Lease::board)
                        .containsExactly("bx-14"));
    }

    @Test
    void aLeaseNobodyIssuedIsANotFound() {
        assertThatThrownBy(() -> engine.sessionScope(session -> new LeaseRepository(session).get("lease-09999")))
                .isInstanceOf(NotFound.class);
    }

    @Test
    void aJobGoesInAndComesBack() {
        engine.sessionScope(session -> {
            new BoardRepository(session).add(BoardSetup.kintex(), available());
            new JobRepository(session).add(job());
        });
        engine.sessionScope(session -> {
            ProgramJob found = new JobRepository(session).get("job-00001");
            assertThat(found.mode()).isEqualTo(JobMode.JTAG);
            assertThat(found.state()).isEqualTo(JobState.QUEUED);
        });
    }

    @Test
    void aHeldJobIsNotOfferedUntilItsBackoffPasses() {
        engine.sessionScope(session -> {
            new BoardRepository(session).add(BoardSetup.kintex(), available());
            new JobRepository(session).add(job(), Optional.of(NOW.plus(Duration.ofSeconds(30))));
        });
        engine.sessionScope(session -> {
            JobRepository repo = new JobRepository(session);
            assertThat(repo.queued(NOW)).isEmpty();
            assertThat(repo.queued(NOW.plus(Duration.ofSeconds(31)))).hasSize(1);
        });
    }

    @Test
    void aRunningJobComesBackFromTheRunningQuery() {
        engine.sessionScope(session -> {
            new BoardRepository(session).add(BoardSetup.kintex(), available());
            new JobRepository(session).add(job());
        });
        engine.sessionScope(session -> {
            JobRepository repo = new JobRepository(session);
            repo.save(repo.get("job-00001").start(NOW));
        });
        engine.sessionScope(session -> {
            JobRepository repo = new JobRepository(session);
            assertThat(repo.running()).extracting(ProgramJob::id).containsExactly("job-00001");
            assertThat(repo.queued(NOW)).isEmpty();
        });
    }

    @Test
    void jobsComeBackByBoardAndByImage() {
        engine.sessionScope(session -> {
            new BoardRepository(session).add(BoardSetup.kintex(), available());
            new JobRepository(session).add(job());
            new JobRepository(session).add(job("job-00002", "cd".repeat(32)));
        });
        engine.sessionScope(session -> {
            JobRepository repo = new JobRepository(session);
            assertThat(repo.forBoard("bx-14")).hasSize(2);
            assertThat(repo.forDigest(DIGEST)).extracting(ProgramJob::id).containsExactly("job-00001");
        });
    }

    @Test
    void anArtifactGoesInAndMovesChannel() {
        engine.sessionScope(session -> new ArtifactRepository(session).add(record(), NOW));
        engine.sessionScope(session -> {
            ArtifactRepository repo = new ArtifactRepository(session);
            repo.save(repo.get(DIGEST).withChannel("qual").tagged("keep"));
        });
        engine.sessionScope(session -> {
            ArtifactRecord found = new ArtifactRepository(session).get(DIGEST);
            assertThat(found.channel()).isEqualTo("qual");
            assertThat(found.tags()).containsExactly("keep");
        });
    }

    @Test
    void artifactsComeBackByDesignAndChannel() {
        engine.sessionScope(session -> {
            ArtifactRepository repo = new ArtifactRepository(session);
            repo.add(record(), NOW);
            repo.add(record("cd".repeat(32), "qual", "beamformer"), NOW);
        });
        engine.sessionScope(session -> {
            ArtifactRepository repo = new ArtifactRepository(session);
            assertThat(repo.inChannel("qual"))
                    .extracting(ArtifactRecord::design)
                    .containsExactly("beamformer");
            assertThat(repo.forDesign("radar_frontend")).hasSize(1);
        });
    }

    @Test
    void anArtifactNobodyStoredIsANotFound() {
        assertThatThrownBy(() -> engine.sessionScope(session -> new ArtifactRepository(session).get("ef".repeat(32))))
                .isInstanceOf(NotFound.class);
    }
}
