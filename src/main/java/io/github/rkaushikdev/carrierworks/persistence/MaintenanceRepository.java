package io.github.rkaushikdev.carrierworks.persistence;

import io.github.rkaushikdev.carrierworks.inventory.MaintenanceBook;
import io.github.rkaushikdev.carrierworks.inventory.MaintenanceKind;
import io.github.rkaushikdev.carrierworks.inventory.MaintenanceRecord;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Every visit to every board, oldest first. */
public final class MaintenanceRepository {

    private final EntityManager session;

    MaintenanceRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** Record one visit. */
    public MaintenanceRecord add(MaintenanceRecord record) {
        MaintenanceRow row = new MaintenanceRow();
        row.setBoard(record.board());
        row.setKind(record.kind().value());
        row.setAt(record.at());
        row.setBy(record.by());
        row.setNotes(record.notes());
        row.setFaultsCleared(record.faultsCleared());
        session.persist(row);
        session.flush();
        return record;
    }

    /** Every visit, oldest first. */
    public List<MaintenanceRecord> all() {
        return records(session.createQuery("select m from MaintenanceRow m order by m.at, m.id", MaintenanceRow.class));
    }

    /** Every visit to one board, oldest first. */
    public List<MaintenanceRecord> forBoard(String board) {
        return records(session.createQuery(
                        "select m from MaintenanceRow m where m.board = :board order by m.at, m.id",
                        MaintenanceRow.class)
                .setParameter("board", lowered(board)));
    }

    /** The newest visit to one board, if it has had any. */
    public Optional<MaintenanceRecord> last(String board) {
        return first(session.createQuery(
                        "select m from MaintenanceRow m where m.board = :board order by m.at desc, m.id desc",
                        MaintenanceRow.class)
                .setParameter("board", lowered(board)));
    }

    /** The newest visit of one kind to one board, if it has had one. */
    public Optional<MaintenanceRecord> last(String board, MaintenanceKind kind) {
        return first(session.createQuery(
                        "select m from MaintenanceRow m where m.board = :board and m.kind = :kind"
                                + " order by m.at desc, m.id desc",
                        MaintenanceRow.class)
                .setParameter("board", lowered(board))
                .setParameter("kind", kind.value()));
    }

    /**
     * The whole log as the book the calibration rules are written against.
     *
     * <p>Loaded in one query rather than asked per board, because every caller of this wants to ask
     * about the whole bench: what is overdue, what was cleared, what has never been calibrated at
     * all.
     */
    public MaintenanceBook book() {
        return new MaintenanceBook(all());
    }

    private static String lowered(String board) {
        return board.strip().toLowerCase(Locale.ROOT);
    }

    private static Optional<MaintenanceRecord> first(TypedQuery<MaintenanceRow> query) {
        return query.setMaxResults(1).getResultStream().findFirst().map(MaintenanceRepository::record);
    }

    private static List<MaintenanceRecord> records(TypedQuery<MaintenanceRow> query) {
        return query.getResultList().stream().map(MaintenanceRepository::record).toList();
    }

    private static MaintenanceRecord record(MaintenanceRow row) {
        return new MaintenanceRecord(
                row.getBoard(),
                MaintenanceKind.parse(row.getKind()),
                row.getAt(),
                row.getBy(),
                row.getNotes(),
                row.getFaultsCleared());
    }
}
