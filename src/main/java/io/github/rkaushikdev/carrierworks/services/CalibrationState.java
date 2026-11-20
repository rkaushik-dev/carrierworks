package io.github.rkaushikdev.carrierworks.services;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

/**
 * Where one board stands on calibration.
 *
 * @param board the board's asset tag
 * @param required whether anything fitted to it measures, so that it needs calibrating at all
 * @param lastAt when it was last calibrated, if it ever was
 * @param dueAt when that calibration runs out, if it does
 * @param valid whether it can be trusted for a measurement now
 */
public record CalibrationState(
        String board, boolean required, Optional<Instant> lastAt, Optional<Instant> dueAt, boolean valid) {

    public CalibrationState {
        Objects.requireNonNull(lastAt, "lastAt");
        Objects.requireNonNull(dueAt, "dueAt");
    }

    /** Whether the board needs calibrating and nobody ever has. */
    public boolean never() {
        return required && lastAt.isEmpty();
    }

    /** {@code bx-14 calibrated 2026-04-03, due 2027-04-03}, or why there is nothing to say. */
    public String describe() {
        if (!required) {
            return board + " needs no calibration";
        }
        if (lastAt.isEmpty()) {
            return board + " has never been calibrated";
        }
        String when = dueAt.map(CalibrationState::day).orElse("no expiry");
        return board + " calibrated " + day(lastAt.get()) + ", due " + when;
    }

    private static String day(Instant moment) {
        return LocalDate.ofInstant(moment, ZoneOffset.UTC).toString();
    }
}
