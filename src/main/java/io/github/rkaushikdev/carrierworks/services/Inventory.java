package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.Conflict;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardBook;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.jtag.AdapterUnit;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Adding boards, moving their state, and changing what is fitted to them.
 *
 * <p>Every one of these goes through the same three steps: read the board, ask the domain object
 * whether what is being asked for is allowed, and write the answer back. The domain refuses the
 * impossible ones, so the service never has a rule of its own to enforce and never has to duplicate
 * one.
 *
 * <p>Faulting a board is the exception worth reading. It has to release whatever holds the board
 * as well as moving it, because a faulted board with a live lease on it is a board nobody can take
 * and the holder cannot use.
 */
public final class Inventory {

    private Inventory() {}

    /**
     * What faulting a board came to.
     *
     * @param state where the board is now
     * @param released the lease that was given back because of it, if one was live
     */
    public record Faulted(BoardState state, Optional<Lease> released) {}

    /**
     * Refuse a board that would collide with one already on the bench.
     *
     * <p>The database enforces all three of these as well, and it is the one that matters when two
     * writers race. This exists so that the ordinary case gets a message naming the board in the way
     * rather than a constraint name.
     *
     * @throws Conflict when another board has the same slot, cable or outlet
     */
    static void checkFree(UnitOfWork unit, Board board) {
        for (Board other : unit.boards().all()) {
            if (other.tag().equals(board.tag())) {
                continue;
            }
            if (other.location().equals(board.location())) {
                throw new Conflict(
                        "another board is already in that slot",
                        Details.of(
                                "board",
                                board.tag(),
                                "holder",
                                other.tag(),
                                "location",
                                board.location().toString()));
            }
            Optional<String> serial = board.adapter().map(AdapterUnit::serial);
            if (serial.isPresent() && serial.equals(other.adapter().map(AdapterUnit::serial))) {
                throw new Conflict(
                        "that cable is already on another board",
                        Details.of("board", board.tag(), "holder", other.tag(), "serial", serial.get()));
            }
            if (board.pdu().isPresent() && other.pdu().equals(board.pdu())) {
                throw new Conflict(
                        "that outlet already feeds another board",
                        Details.of(
                                "board",
                                board.tag(),
                                "holder",
                                other.tag(),
                                "outlet",
                                board.pdu().get().toString()));
            }
        }
    }

    /** Put a board on the bench, available. */
    public static Board addBoard(UnitOfWork unit, Board board, Instant now) {
        return addBoard(unit, board, now, BoardStatus.AVAILABLE);
    }

    /**
     * Put a board on the bench, in whatever state it arrived in.
     *
     * <p>A board arriving out of service is recorded as {@value BoardBook#ADDED_REASON}.
     *
     * @throws Conflict when it would share a slot, a cable or an outlet with a board already there
     */
    public static Board addBoard(UnitOfWork unit, Board board, Instant now, BoardStatus state) {
        checkFree(unit, board);
        String reason = state.needsReason() ? BoardBook.ADDED_REASON : "";
        unit.boards().add(board, new BoardState(state, now, reason));
        return board;
    }

    /** Every board, in tag order. */
    public static List<Board> listBoards(UnitOfWork unit) {
        return listBoards(unit, false);
    }

    /** Every board, or only the ones that could be used right now, in tag order. */
    public static List<Board> listBoards(UnitOfWork unit, boolean onlyAvailable) {
        return onlyAvailable ? unit.boards().available() : unit.boards().all();
    }

    /** {@link #moveState(UnitOfWork, String, BoardStatus, Instant, String, String)} by the bench, giving no reason. */
    public static BoardState moveState(UnitOfWork unit, String tag, BoardStatus toState, Instant now) {
        return moveState(unit, tag, toState, now, "", "");
    }

    /**
     * Move a board's state through the state machine's own rules, and write a line about it.
     *
     * @param actor who moved it, or empty for the bench itself
     * @throws io.github.rkaushikdev.carrierworks.ValidationError when the move is not one the state
     *     machine allows, or the new state needs a reason and none was given
     */
    public static BoardState moveState(
            UnitOfWork unit, String tag, BoardStatus toState, Instant now, String reason, String actor) {
        BoardState current = unit.boards().state(tag);
        BoardState moved = current.move(toState, now, reason, actor);
        unit.boards().saveState(tag, moved);
        Audit.record(
                unit,
                now,
                "board-state",
                actor.isEmpty() ? Audit.BENCH : actor,
                Records.boardKey(tag),
                (current.state().value() + " to " + moved.state().value() + " " + reason).strip());
        return moved;
    }

    /** {@link #faultBoard(UnitOfWork, String, Instant, String, String)} by the bench itself. */
    public static Faulted faultBoard(UnitOfWork unit, String tag, Instant now, String reason) {
        return faultBoard(unit, tag, now, reason, Audit.BENCH);
    }

    /**
     * Take a board out of service and give back whatever held it.
     *
     * <p>A board that is already faulted stays where it is, though a lease still on it is given
     * back all the same.
     */
    public static Faulted faultBoard(UnitOfWork unit, String tag, Instant now, String reason, String actor) {
        Optional<Lease> released = unit.leases().forBoard(tag, now).map(held -> {
            Lease closed = held.release(now, "board faulted");
            unit.leases().save(closed);
            return closed;
        });
        BoardState current = unit.boards().state(tag);
        if (current.state() == BoardStatus.FAULTED) {
            return new Faulted(current, released);
        }
        BoardState moved = current.move(BoardStatus.FAULTED, now, reason, actor);
        unit.boards().saveState(tag, moved);
        Audit.record(unit, now, "board-faulted", actor, Records.boardKey(tag), reason);
        return new Faulted(moved, released);
    }

    /**
     * Record that somebody has fitted a card to a slot, or emptied it.
     *
     * <p>The board rebuilds itself and refuses the combinations that cannot work, so a card in the
     * wrong slot is caught here rather than at the next build.
     *
     * @param card the card now in the slot, or empty when it was taken out
     */
    public static Board fitCard(UnitOfWork unit, String tag, String slot, Optional<String> card) {
        Board board = unit.boards().get(tag);
        Board refitted = card.map(name -> board.withCard(slot, name)).orElseGet(() -> board.withoutCard(slot));
        return unit.boards().replace(refitted);
    }
}
