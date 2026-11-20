package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.jtag.ScanSession;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * How the runner reaches a board: a scan session for a JTAG load, a flash driver for a write, each
 * looked up by the board's tag.
 *
 * <p>Either can be missing. A job that needs the missing one fails as a not found rather than
 * pretending, which is how a bench with no cable for a board says so.
 *
 * @param sessions the session to load a board through, or empty when there is none
 * @param flashes the flash driver to write a board through, or empty when there is none
 */
public record BoardDrivers(
        Optional<Function<String, ScanSession>> sessions, Optional<Function<String, SpiFlashDriver>> flashes) {

    public BoardDrivers {
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(flashes, "flashes");
    }

    /** Both ways in. */
    public static BoardDrivers of(Function<String, ScanSession> sessions, Function<String, SpiFlashDriver> flashes) {
        return new BoardDrivers(Optional.of(sessions), Optional.of(flashes));
    }
}
