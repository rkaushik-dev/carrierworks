package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import java.time.Instant;
import java.util.List;

/**
 * Boards, their state, and the one way state is allowed to change.
 *
 * <p>This and {@link LeaseRegistry} and {@link JobLedger} are what the deciding parts of the bench
 * (the job runner, recovery and the thermal supervisor) ask of an inventory, and no more. The
 * in-memory board book and the database both answer them, which is what keeps those callers
 * testable without a database. The set is small on purpose: anything that wants more is doing
 * inventory work and belongs in the service layer, where it can use the repositories directly and
 * take the transaction with it.
 */
public interface BoardRegistry {

    /** Whether the board is known. */
    boolean has(String tag);

    /** One board, or a not found. */
    Board board(String tag);

    /** Where one board is now. */
    BoardState state(String tag);

    /** Every board, in tag order. */
    List<Board> all();

    /** Every board in one state, in tag order. */
    List<Board> inState(BoardStatus state);

    /** Move a board's state, saying why and who did it. */
    BoardState moveTo(String tag, BoardStatus target, Instant when, String reason, String actor);

    /** Move a board's state, saying why. */
    default BoardState moveTo(String tag, BoardStatus target, Instant when, String reason) {
        return moveTo(tag, target, when, reason, "");
    }

    /** Move a board's state. */
    default BoardState moveTo(String tag, BoardStatus target, Instant when) {
        return moveTo(tag, target, when, "", "");
    }
}
