package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Where a board is now, since when, and why.
 *
 * @param state the state
 * @param since when the board entered it
 * @param reason why, which the out of service states insist on
 * @param actor who moved it, or empty
 */
public record BoardState(BoardStatus state, Instant since, String reason, String actor) {

    public BoardState {
        if (state.needsReason() && reason.isBlank()) {
            throw new ValidationError("that state needs a reason", "reason", Details.of("state", state.value()));
        }
    }

    /** A state nobody gave a reason for and nobody in particular set. */
    public BoardState(BoardStatus state, Instant since) {
        this(state, since, "", "");
    }

    /** A state with a reason and nobody in particular named. */
    public BoardState(BoardStatus state, Instant since, String reason) {
        this(state, since, reason, "");
    }

    /** Whether a job could be scheduled onto a board in this state. */
    public boolean usable() {
        return state.usable();
    }

    /** Whether somebody is using the board. */
    public boolean busy() {
        return state.busy();
    }

    /** Whether the board is out of service. */
    public boolean outOfService() {
        return state.outOfService();
    }

    /** Whether there is nowhere left to go. */
    public boolean terminal() {
        return state.terminal();
    }

    /** Everywhere the board may go from here. */
    public List<BoardStatus> moves() {
        return state.moves();
    }

    /** {@link #move(BoardStatus, Instant, String, String)} with no reason and nobody named. */
    public BoardState move(BoardStatus target, Instant when) {
        return move(target, when, "", "");
    }

    /** {@link #move(BoardStatus, Instant, String, String)} with nobody named. */
    public BoardState move(BoardStatus target, Instant when, String reason) {
        return move(target, when, reason, "");
    }

    /** The next state, or a refusal naming what was allowed instead. */
    public BoardState move(BoardStatus target, Instant when, String reason, String actor) {
        if (!state.canMoveTo(target)) {
            String allowed = moves().stream().map(BoardStatus::value).collect(Collectors.joining(", "));
            throw new ValidationError(
                    "a board cannot go from " + state.value() + " to " + target.value(),
                    "state",
                    Details.of("allowed", allowed.isEmpty() ? "nowhere" : allowed));
        }
        if (when.isBefore(since)) {
            throw new ValidationError("a board cannot change state before it entered the last one", "when");
        }
        return new BoardState(target, when, reason, actor);
    }

    /** How many whole seconds the board has been in this state. */
    public long heldSeconds(Instant now) {
        if (now.isBefore(since)) {
            throw new ValidationError("that is before the board entered this state", "now");
        }
        return Times.seconds(Duration.between(since, now));
    }

    /** {@code faulted since 2025-07-09T14:00:00+00:00: chain read as all ones (nightly)}. */
    public String describe() {
        String line = state.value() + " since " + Times.iso(since, Times.Precision.SECONDS);
        if (!reason.isEmpty()) {
            line = line + ": " + reason;
        }
        if (!actor.isEmpty()) {
            line = line + " (" + actor + ")";
        }
        return line;
    }
}
