package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * One visit to the bench with a screwdriver or a reference source.
 *
 * @param board the asset tag, normalised
 * @param kind what was done
 * @param at when it was done
 * @param by who did it, never blank
 * @param notes what they wrote down, or empty
 * @param faultsCleared how many faults the visit cleared off the board
 */
public record MaintenanceRecord(
        String board, MaintenanceKind kind, Instant at, String by, String notes, int faultsCleared) {

    public MaintenanceRecord {
        board = Records.boardKey(board);
        if (by.isBlank()) {
            throw new ValidationError("somebody did the work", "by");
        }
        if (faultsCleared < 0) {
            throw new ValidationError("cannot clear a negative number of faults", "faults");
        }
    }

    /** A visit nobody wrote notes about and that cleared nothing. */
    public MaintenanceRecord(String board, MaintenanceKind kind, Instant at, String by) {
        this(board, kind, at, by, "", 0);
    }

    /** {@code bx-14 repair on 2025-07-25 by dana: reflowed the jtag header}. */
    public String describe() {
        String line = board + " " + kind + " on " + LocalDate.ofInstant(at, ZoneOffset.UTC) + " by " + by;
        return notes.isEmpty() ? line : line + ": " + notes;
    }
}
