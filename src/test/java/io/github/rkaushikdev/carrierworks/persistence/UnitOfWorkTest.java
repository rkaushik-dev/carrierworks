package io.github.rkaushikdev.carrierworks.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnitOfWorkTest {

    private static final Instant NOW = Instant.parse("2025-12-11T10:00:00Z");

    @TempDir
    Path workspace;

    private BenchDatabase database;

    @BeforeEach
    void open() {
        String url = "jdbc:h2:file:" + workspace.resolve("bench").toAbsolutePath();
        database = new BenchDatabase(BenchSettings.builder().databaseUrl(url).build());
        database.createSchema();
    }

    @AfterEach
    void close() {
        database.close();
    }

    private static BoardState available() {
        return new BoardState(BoardStatus.AVAILABLE, NOW);
    }

    private static Lease lease() {
        return new Lease("lease-00001", "bx-14", "team-a", "capture", NOW, NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void aUnitCommitsOnTheWayOut() {
        database.run(unit -> unit.boards().add(BoardSetup.kintex(), available()));
        database.run(unit -> assertThat(unit.boards().get("bx-14").tag()).isEqualTo("bx-14"));
    }

    @Test
    void aUnitThatRaisesWritesNothing() {
        assertThatThrownBy(() -> database.run(unit -> {
                    unit.boards().add(BoardSetup.kintex(), available());
                    throw new IllegalStateException("something went wrong halfway");
                }))
                .isInstanceOf(IllegalStateException.class);
        database.run(unit -> assertThat(unit.boards().all()).isEmpty());
    }

    @Test
    void fourWritesLandTogetherOrNotAtAll() {
        assertThatThrownBy(() -> database.run(unit -> {
                    unit.boards().add(BoardSetup.kintex(), available());
                    unit.leases().add(lease());
                    throw new IllegalStateException("the job blew up");
                }))
                .isInstanceOf(IllegalStateException.class);
        database.run(unit -> {
            assertThat(unit.boards().all()).isEmpty();
            assertThat(unit.leases().active(NOW)).isEmpty();
        });
    }

    @Test
    void theBoardTheLeaseAndTheJobAllLand() {
        database.run(unit -> {
            unit.boards().add(BoardSetup.kintex(), available());
            unit.leases().add(lease());
            unit.boards().saveState("bx-14", new BoardState(BoardStatus.LEASED, NOW));
        });
        database.run(unit -> {
            assertThat(unit.boards().state("bx-14").state()).isEqualTo(BoardStatus.LEASED);
            assertThat(unit.leases().forBoard("bx-14", NOW)).isPresent();
        });
    }

    @Test
    void aUnitCanBeRolledBackByHand() {
        database.run(unit -> {
            unit.boards().add(BoardSetup.kintex(), available());
            unit.rollback();
        });
        database.run(unit -> assertThat(unit.boards().all()).isEmpty());
    }

    @Test
    void aUnitThatCommittedDoesNotCommitAgain() {
        database.run(unit -> {
            unit.boards().add(BoardSetup.kintex(), available());
            unit.commit();
            assertThat(unit.committed()).isTrue();
        });
        database.run(unit -> assertThat(unit.boards().all()).hasSize(1));
    }

    @Test
    void aFlushMakesAWriteVisibleInsideTheSameUnit() {
        database.run(unit -> {
            unit.boards().add(BoardSetup.kintex(), available());
            unit.flush();
            assertThat(unit.boards().get("bx-14").model()).isEqualTo("rx-carrier-b");
        });
    }

    @Test
    void aUnitOutsideItsBlockRefusesToBeUsed() {
        UnitOfWork unit = database.unit();
        assertThatThrownBy(() -> unit.boards().all()).isInstanceOf(ValidationError.class);
    }

    @Test
    void aUnitIsUsableAgainAfterItIsReopened() {
        UnitOfWork unit = database.unit();
        unit.run(open -> open.boards().add(BoardSetup.kintex(), available()));
        unit.run(open -> assertThat(open.boards().all()).hasSize(1));
    }

    @Test
    void anInMemoryDatabaseHoldsAWholeBench() {
        BenchDatabase live = BenchDatabase.inMemory();
        live.run(unit -> {
            unit.boards().add(BoardSetup.kintex(), available());
            unit.boards().add(BoardSetup.artix(), available());
        });
        live.run(unit -> assertThat(unit.boards().all()).hasSize(2));
        live.dispose();
    }

    @Test
    void aMissingRowStillRaisesThroughTheUnit() {
        assertThatThrownBy(() -> database.run(unit -> unit.boards().get("bx-99")))
                .isInstanceOf(NotFound.class);
    }

    @Test
    void theRepositoriesComeOffTheSameSession() {
        database.run(unit -> {
            assertThat(unit.boards().session()).isSameAs(unit.leases().session());
            assertThat(unit.jobs().session()).isSameAs(unit.artifacts().session());
        });
    }
}
