package io.github.rkaushikdev.carrierworks.thermal;

import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.Temperature;
import io.github.rkaushikdev.carrierworks.ThermalState;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalInt;

/**
 * Where one board stands thermally.
 *
 * <p>{@link ThermalState#UNKNOWN} is a state here, not a missing value: a board that has stopped
 * reporting is one the bench cannot reason about, and it is reported as such.
 *
 * @param board the board's asset tag
 * @param state the band the board is in
 * @param since when it entered that band
 * @param millidegrees the reading the state was decided on, or the last one heard when stale
 * @param stale whether the bench has stopped hearing from the board
 */
public record ThermalStatus(String board, ThermalState state, Instant since, OptionalInt millidegrees, boolean stale) {

    public ThermalStatus {
        board = Records.boardKey(board);
    }

    /** A status with no reading behind it, from a board that is not stale. */
    public ThermalStatus(String board, ThermalState state, Instant since) {
        this(board, state, since, OptionalInt.empty(), false);
    }

    /** Whether the board is in the normal band. */
    public boolean healthy() {
        return state == ThermalState.NORMAL;
    }

    /** Anything a person should be told about, silence included. */
    public boolean alarming() {
        return state == ThermalState.CRITICAL || state == ThermalState.SHUTDOWN || state == ThermalState.UNKNOWN;
    }

    /** How bad this is, for sorting. Unknown sits with critical. */
    public int rank() {
        return state == ThermalState.UNKNOWN ? ThermalState.CRITICAL.level() : state.level();
    }

    /** How long the board has been in this state; a since in the future counts as no time. */
    public Duration heldFor(Instant now) {
        Duration held = Duration.between(since, now);
        return held.isNegative() ? Duration.ZERO : held;
    }

    /** {@code bx-14 warn at 78.0C}, or {@code bx-14 not reporting} when the state is unknown. */
    public String describe() {
        if (state == ThermalState.UNKNOWN) {
            return board + " not reporting";
        }
        String reading = millidegrees.isPresent() ? Temperature.render(millidegrees.getAsInt()) : "no reading";
        return board + " " + state.value() + " at " + reading;
    }
}
