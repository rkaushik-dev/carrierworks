package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.BenchError;
import io.github.rkaushikdev.carrierworks.BoardRegistry;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardState;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The board repository wearing the shape of the in-memory board book.
 *
 * <p>The job runner, recovery and the thermal supervisor are written against a {@link
 * BoardRegistry}, which is why they are testable without a database, and that is worth keeping.
 * This gives them the same handful of methods over the database instead, so the same code runs in a
 * test with everything in memory and on the bench with everything stored.
 *
 * <p>It is deliberately thin: only what those callers use is here. A wider adapter would be a second
 * inventory with its own opinions, which is the thing this exists to avoid. A state change goes
 * through {@link Inventory#moveState}, so it writes its audit line like any other.
 */
public final class BookOverRepository implements BoardRegistry {

    private final UnitOfWork unit;

    /** A board registry reading and writing through this unit of work. */
    public BookOverRepository(UnitOfWork unit) {
        this.unit = Objects.requireNonNull(unit, "unit");
    }

    /** Whether the board is known; a tag that is not even well formed is simply not known. */
    @Override
    public boolean has(String tag) {
        try {
            unit.boards().get(tag);
        } catch (BenchError missing) {
            return false;
        }
        return true;
    }

    @Override
    public Board board(String tag) {
        return unit.boards().get(tag);
    }

    @Override
    public BoardState state(String tag) {
        return unit.boards().state(tag);
    }

    @Override
    public List<Board> all() {
        return unit.boards().all();
    }

    /** Every board that could be used right now, in tag order. */
    public List<Board> available() {
        return unit.boards().available();
    }

    @Override
    public List<Board> inState(BoardStatus state) {
        return unit.boards().all().stream()
                .filter(board -> unit.boards().state(board.tag()).state() == state)
                .toList();
    }

    @Override
    public BoardState moveTo(String tag, BoardStatus target, Instant when, String reason, String actor) {
        return Inventory.moveState(unit, tag, target, when, reason, actor);
    }
}
