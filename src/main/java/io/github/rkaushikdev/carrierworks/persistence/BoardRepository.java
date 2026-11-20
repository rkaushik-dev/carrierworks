package io.github.rkaushikdev.carrierworks.persistence;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSlot;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;

/**
 * Boards and the state that hangs off them.
 *
 * <p>Every repository takes a session and hands back domain objects. No SQL escapes into a
 * service and no service assembles a query, which is what keeps the http layer and the command line
 * reading the same rows the same way. The queries are the ones the bench has needed, not a general
 * purpose interface.
 */
public final class BoardRepository {

    private final EntityManager session;

    BoardRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    private BoardRow row(String tag) {
        String key = Records.boardKey(tag);
        BoardRow row = session.find(BoardRow.class, key);
        if (row == null) {
            throw new NotFound("board", key);
        }
        return row;
    }

    /** Put a new board on the bench in the state given. */
    public Board add(Board board, BoardState state) {
        BoardRow row = Mappers.boardToRow(board);
        row.setState(Mappers.stateToRow(board.tag(), state));
        session.persist(row);
        return board;
    }

    /** One board, or a not found. */
    public Board get(String tag) {
        return Mappers.boardFromRow(row(tag));
    }

    /** Where one board is now, or a not found when it has no state on record. */
    public BoardState state(String tag) {
        BoardRow row = row(tag);
        return Mappers.stateFromRow(row.getState().orElseThrow(() -> new NotFound("board state", row.getTag())));
    }

    /** Record a board's new state. */
    public BoardState saveState(String tag, BoardState state) {
        BoardRow row = row(tag);
        row.getState()
                .ifPresentOrElse(
                        existing -> Mappers.copyState(state, existing),
                        () -> row.setState(Mappers.stateToRow(row.getTag(), state)));
        return state;
    }

    /**
     * Write a changed board back, slots and all.
     *
     * <p>The columns are copied onto the row that is already in the session rather than by adding a
     * second row with the same key, which would be an identity conflict at the next flush. The slots
     * are cleared, flushed, and then rebuilt: clearing and adding in one flush would leave the
     * insert of a slot with the same name ahead of the delete, and trip the unique constraint.
     */
    public Board replace(Board board) {
        BoardRow row = row(board.tag());
        Mappers.copyBoard(board, row);
        row.clearSlots();
        session.flush();
        for (BoardSlot slot : board.slots()) {
            row.addSlot(Mappers.slotToRow(slot));
        }
        return board;
    }

    /** Every board, in tag order. */
    public List<Board> all() {
        return boards(session.createQuery(
                        "select distinct b from BoardRow b left join fetch b.slots order by b.tag", BoardRow.class)
                .getResultList());
    }

    /** Every board whose state says it can be used, in tag order. */
    public List<Board> available() {
        return boards(session.createQuery(
                        "select distinct b from BoardRow b join b.state s left join fetch b.slots"
                                + " where s.available = true order by b.tag",
                        BoardRow.class)
                .getResultList());
    }

    /** Every board in one rack, in tag order. */
    public List<Board> inRack(String rack) {
        return boards(session.createQuery(
                        "select distinct b from BoardRow b left join fetch b.slots where b.rack = :rack order by b.tag",
                        BoardRow.class)
                .setParameter("rack", rack.strip().toLowerCase(Locale.ROOT))
                .getResultList());
    }

    /** Take a board off the bench, slots and state with it. */
    public void remove(String tag) {
        session.remove(row(tag));
    }

    private static List<Board> boards(List<BoardRow> rows) {
        return rows.stream().map(Mappers::boardFromRow).toList();
    }
}
