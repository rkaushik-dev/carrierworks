package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.thermal.ThermalStatus;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Instant;
import java.util.OptionalInt;

/**
 * Where one board stands thermally.
 *
 * @param board the board
 * @param state its thermal state
 * @param since when it entered that state
 * @param heldForSeconds how long it has been in it, in whole seconds
 * @param millidegrees its latest reading, or null when it has none in the window
 * @param stale whether its readings have stopped arriving
 * @param alarming whether its state is one somebody should act on
 */
public record StatusOut(
        String board,
        String state,
        Instant since,
        long heldForSeconds,
        OptionalInt millidegrees,
        boolean stale,
        boolean alarming) {

    /** One board's status, measured at a moment. */
    public static StatusOut of(ThermalStatus status, Instant moment) {
        return new StatusOut(
                status.board(),
                status.state().value(),
                status.since(),
                Times.seconds(status.heldFor(moment)),
                status.millidegrees(),
                status.stale(),
                status.alarming());
    }
}
