package io.github.rkaushikdev.carrierworks.persistence;

import static io.github.rkaushikdev.carrierworks.persistence.Constraints.refused;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersistenceModelsTest {

    private static final Instant NOW = Instant.parse("2025-11-18T10:00:00Z");

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

    private static BoardRow boardRow() {
        return boardRow("bx-14", "rack-2", 4, 3, "210308AB1234", 7);
    }

    private static BoardRow boardRow(String tag, String rack, int shelf, int slot, String serial, int outlet) {
        BoardRow row = new BoardRow();
        row.setTag(tag);
        row.setModel("rx-carrier-b");
        row.setPart("xc7k325t-2ffg900i");
        row.setDevice("xc7k325t");
        row.setFlash("n25q128a13");
        row.setRack(rack);
        row.setShelf(shelf);
        row.setSlot(slot);
        row.setAdapterSerial(serial);
        row.setAdapterModel("jtag-hs2");
        row.setPdu("pdu-a");
        row.setOutlet(outlet);
        return row;
    }

    private static SlotRow slot(String name, String connector, String fitted) {
        SlotRow row = new SlotRow();
        row.setName(name);
        row.setConnector(connector);
        row.setFitted(fitted);
        return row;
    }

    @Test
    void theSchemaHoldsTheTablesTheBenchNeeds() {
        EntityManagerFactory factory = engine.sessions();
        Set<String> names = factory.getMetamodel().getEntities().stream()
                .map(EntityType::getJavaType)
                .map(type -> type.getAnnotation(jakarta.persistence.Table.class).name())
                .collect(Collectors.toSet());
        assertThat(names).contains("boards", "board_slots", "board_states");
    }

    @Test
    void aBoardRoundTrips() {
        engine.sessionScope(session -> session.persist(boardRow()));
        engine.sessionScope(session -> {
            BoardRow found = session.find(BoardRow.class, "bx-14");
            assertThat(found).isNotNull();
            assertThat(found.getDevice()).isEqualTo("xc7k325t");
            assertThat(found.getRevision()).isEqualTo("a");
            assertThat(found.getMaxTckHz()).isEqualTo(30_000_000);
        });
    }

    @Test
    void slotsComeBackWithTheirBoard() {
        engine.sessionScope(session -> {
            BoardRow board = boardRow();
            board.addSlot(slot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b"));
            board.addSlot(slot("fmc1", "fmc-lpc", null));
            session.persist(board);
        });
        engine.sessionScope(session -> {
            BoardRow found = session.find(BoardRow.class, "bx-14");
            assertThat(found.getSlots()).extracting(SlotRow::getName).containsExactly("fmc0", "fmc1");
            assertThat(found.getSlots().get(1).getFitted()).isNull();
        });
    }

    @Test
    void twoBoardsCannotShareASlotInTheRack() {
        engine.sessionScope(session -> session.persist(boardRow()));
        refused(() ->
                engine.sessionScope(session -> session.persist(boardRow("bx-15", "rack-2", 4, 3, "210308CD5678", 9))));
    }

    @Test
    void twoBoardsCannotShareACable() {
        engine.sessionScope(session -> session.persist(boardRow()));
        refused(() ->
                engine.sessionScope(session -> session.persist(boardRow("bx-15", "rack-2", 5, 3, "210308AB1234", 9))));
    }

    @Test
    void twoBoardsCannotShareAnOutlet() {
        engine.sessionScope(session -> session.persist(boardRow()));
        refused(() ->
                engine.sessionScope(session -> session.persist(boardRow("bx-15", "rack-2", 5, 3, "210308CD5678", 7))));
    }

    @Test
    void aShelfOutsideTheRackIsRefused() {
        refused(() ->
                engine.sessionScope(session -> session.persist(boardRow("bx-14", "rack-2", 99, 3, "210308AB1234", 7))));
    }

    @Test
    void aSlotOutsideTheShelfIsRefused() {
        refused(() ->
                engine.sessionScope(session -> session.persist(boardRow("bx-14", "rack-2", 4, 9, "210308AB1234", 7))));
    }

    @Test
    void twoSlotsOnOneBoardCannotShareAName() {
        refused(() -> engine.sessionScope(session -> {
            BoardRow board = boardRow();
            board.addSlot(slot("fmc0", "fmc-hpc", null));
            board.addSlot(slot("fmc0", "fmc-lpc", null));
            session.persist(board);
        }));
    }

    @Test
    void aStateHangsOffItsBoard() {
        engine.sessionScope(session -> {
            BoardRow board = boardRow();
            BoardStateRow state = new BoardStateRow();
            state.setState("available");
            state.setSince(NOW);
            state.setAvailable(true);
            board.setState(state);
            session.persist(board);
        });
        engine.sessionScope(session -> {
            BoardRow found = session.find(BoardRow.class, "bx-14");
            assertThat(found.getState()).isPresent();
            assertThat(found.getState().get().getState()).isEqualTo("available");
            assertThat(found.getState().get().isAvailable()).isTrue();
        });
    }

    @Test
    void deletingABoardTakesItsSlotsAndStateWithIt() {
        engine.sessionScope(session -> {
            BoardRow board = boardRow();
            board.addSlot(slot("fmc0", "fmc-hpc", null));
            BoardStateRow state = new BoardStateRow();
            state.setState("available");
            state.setSince(NOW);
            board.setState(state);
            session.persist(board);
        });
        engine.sessionScope(session -> session.remove(session.find(BoardRow.class, "bx-14")));
        engine.sessionScope(session -> {
            assertThat(session.createQuery("select s from SlotRow s", SlotRow.class)
                            .getResultList())
                    .isEmpty();
            assertThat(session.createQuery("select s from BoardStateRow s", BoardStateRow.class)
                            .getResultList())
                    .isEmpty();
        });
    }

    @Test
    void aSlotCannotHangOffABoardThatIsNotThere() {
        refused(() -> engine.sessionScope(session -> {
            SlotRow orphan = slot("fmc0", "fmc-hpc", null);
            orphan.setBoardTag("bx-99");
            session.persist(orphan);
        }));
    }

    @Test
    void aFailedWriteLeavesNothingBehind() {
        refused(() -> engine.sessionScope(session -> {
            session.persist(boardRow());
            session.persist(boardRow("bx-15", "rack-2", 4, 3, "210308CD5678", 9));
        }));
        engine.sessionScope(session -> assertThat(session.createQuery("select b from BoardRow b", BoardRow.class)
                        .getResultList())
                .isEmpty());
    }

    @Test
    void boardsCanBeFoundByDevice() {
        engine.sessionScope(session -> {
            session.persist(boardRow());
            session.persist(boardRow("bx-15", "rack-2", 5, 3, "210308CD5678", 9));
        });
        engine.sessionScope(session -> assertThat(
                        session.createQuery("select b from BoardRow b where b.device = 'xc7k325t'", BoardRow.class)
                                .getResultList())
                .extracting(BoardRow::getTag)
                .containsExactlyInAnyOrder("bx-14", "bx-15"));
    }
}
