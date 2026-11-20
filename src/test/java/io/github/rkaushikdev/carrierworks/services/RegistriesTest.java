package io.github.rkaushikdev.carrierworks.services;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.BoardRegistry;
import io.github.rkaushikdev.carrierworks.LeaseRegistry;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardBook;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.leases.LeaseStore;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegistriesTest {

    private static final Instant WHEN = Instant.parse("2026-03-02T09:00:00Z");

    private BoardBook book;
    private BenchDatabase database;

    @BeforeEach
    void open() {
        book = new BoardBook();
        book.add(BoardSetup.kintex("bx-14").build(), WHEN.minus(Duration.ofDays(1)), BoardStatus.AVAILABLE, "");
        database = BenchDatabase.inMemory();
        database.createSchema();
    }

    @AfterEach
    void close() {
        database.close();
    }

    /** Run a case in one unit of work, on a stored bench holding bx-14. */
    private void onBench(Consumer<UnitOfWork> body) {
        database.run(unit -> {
            Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), WHEN.minus(Duration.ofDays(1)));
            body.accept(unit);
        });
    }

    @Test
    void theInMemoryBookSatisfiesTheBoardRegistry() {
        assertThat(book).isInstanceOf(BoardRegistry.class);
    }

    @Test
    void theRepositoryAdapterSatisfiesTheBoardRegistry() {
        onBench(unit -> assertThat(new BookOverRepository(unit)).isInstanceOf(BoardRegistry.class));
    }

    @Test
    void theInMemoryStoreSatisfiesTheLeaseRegistry() {
        assertThat(new LeaseStore()).isInstanceOf(LeaseRegistry.class);
    }

    @Test
    void theRepositoryAdapterSatisfiesTheLeaseRegistry() {
        onBench(unit -> assertThat(new LeaseStoreOverRepository(unit)).isInstanceOf(LeaseRegistry.class));
    }

    /** Answering to some of a registry's methods does not make something a registry. */
    @Test
    void somethingMissingAMethodIsNotABoardRegistry() {
        final class Half {

            boolean has(String tag) {
                return false;
            }

            Board board(String tag) {
                throw new NotFound("board", tag);
            }
        }

        Half half = new Half();
        assertThat(half.has("bx-14")).isFalse();
        assertThat((Object) half).isNotInstanceOf(BoardRegistry.class);
    }

    @Test
    void bothRegistriesAnswerTheSameWayAboutABoard() {
        onBench(unit -> {
            BookOverRepository overDatabase = new BookOverRepository(unit);
            assertThat(overDatabase.has("bx-14")).isEqualTo(book.has("bx-14"));
            assertThat(overDatabase.board("bx-14").model())
                    .isEqualTo(book.board("bx-14").model());
            assertThat(overDatabase.state("bx-14").state())
                    .isEqualTo(book.state("bx-14").state());
        });
    }

    @Test
    void bothRegistriesAgreeAboutABoardNobodyHas() {
        onBench(unit -> {
            assertThat(book.has("bx-99")).isFalse();
            assertThat(new BookOverRepository(unit).has("bx-99")).isFalse();
        });
    }

    @Test
    void aMoveThroughEitherRegistryLandsInTheSameState() {
        onBench(unit -> {
            book.moveTo("bx-14", BoardStatus.MAINTENANCE, WHEN, "fan", "ops");
            BookOverRepository overDatabase = new BookOverRepository(unit);
            overDatabase.moveTo("bx-14", BoardStatus.MAINTENANCE, WHEN, "fan", "ops");
            assertThat(overDatabase.state("bx-14").state())
                    .isEqualTo(book.state("bx-14").state());
        });
    }

    @Test
    void theLeaseRegistriesAgreeAboutAnEmptyBoard() {
        onBench(unit -> {
            assertThat(new LeaseStore().forBoard("bx-14", WHEN)).isEmpty();
            assertThat(new LeaseStoreOverRepository(unit).forBoard("bx-14", WHEN))
                    .isEmpty();
        });
    }

    @Test
    void aLeaseTakenThroughTheDatabaseShowsUpOnTheAdapter() {
        onBench(unit -> {
            Leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN, 30);
            assertThat(new LeaseStoreOverRepository(unit).forBoard("bx-14", WHEN))
                    .map(Lease::holder)
                    .contains("team-a");
        });
    }

    @Test
    void releasingThroughTheAdapterGivesTheBoardBack() {
        onBench(unit -> {
            Lease taken = Leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN, 30);
            LeaseStoreOverRepository adapter = new LeaseStoreOverRepository(unit);
            adapter.release(taken.id(), WHEN.plus(Duration.ofMinutes(5)), "done");
            assertThat(adapter.forBoard("bx-14", WHEN.plus(Duration.ofMinutes(6))))
                    .isEmpty();
        });
    }
}
