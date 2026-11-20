package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.inventory.MaintenanceBook;
import io.github.rkaushikdev.carrierworks.inventory.MaintenanceKind;
import io.github.rkaushikdev.carrierworks.inventory.MaintenanceRecord;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Maintenance visits, calibration dates, and getting a board back in service.
 *
 * <p>Two rules live here rather than in the maintenance book, because both of them need the rest of
 * the bench. A board coming back from a visit goes to available only if nothing else is wrong with
 * it, and a board whose calibration has run out cannot be handed to somebody who needs a
 * measurement, whatever state it is in.
 *
 * <p>Calibration is the part people get wrong. It expires on a date, not on use, so a board sitting
 * in a drawer goes out of calibration exactly as fast as one that runs every night, and the bench
 * has to say so before somebody trusts a capture off it.
 */
public final class Upkeep {

    /** The kind of visit that returns a board to service unless another is named. */
    public static final String REPAIR = MaintenanceKind.REPAIR.value();

    private Upkeep() {}

    /** {@link #logVisit(UnitOfWork, String, String, Instant, String, String, int)} with no notes and nothing cleared. */
    public static MaintenanceRecord logVisit(UnitOfWork unit, String board, String kind, Instant at, String by) {
        return logVisit(unit, board, kind, at, by, "", 0);
    }

    /**
     * Write down a visit, refusing a kind the bench does not know.
     *
     * @throws ValidationError when the kind is not one of the bench's, naming the ones it knows
     * @throws io.github.rkaushikdev.carrierworks.NotFound when the board is not on the bench
     */
    public static MaintenanceRecord logVisit(
            UnitOfWork unit, String board, String kind, Instant at, String by, String notes, int faultsCleared) {
        String wanted = kind.strip().toLowerCase(Locale.ROOT);
        MaintenanceKind known = Arrays.stream(MaintenanceKind.values())
                .filter(candidate -> candidate.value().equals(wanted))
                .findFirst()
                .orElseThrow(() -> new ValidationError(
                        "no such maintenance kind", "kind", Details.of("given", kind, "known", knownKinds())));
        unit.boards().get(board);
        return unit.maintenance().add(new MaintenanceRecord(board, known, at, by, notes, faultsCleared));
    }

    /** When this board was last calibrated and whether that still holds. */
    public static CalibrationState calibration(UnitOfWork unit, String board, Instant now) {
        Board found = unit.boards().get(board);
        MaintenanceBook book = unit.maintenance().book();
        return new CalibrationState(
                found.tag(),
                MaintenanceBook.calibrationInterval(found).isPresent(),
                book.calibratedAt(found.tag()),
                book.calibrationDue(found, now),
                book.calibrationValid(found, now));
    }

    /** Every board whose calibration has run out or never happened, in tag order. */
    public static List<CalibrationState> overdue(UnitOfWork unit, Instant now) {
        MaintenanceBook book = unit.maintenance().book();
        return book.overdue(unit.boards().all(), now).stream()
                .map(board -> calibration(unit, board.tag(), now))
                .toList();
    }

    /** {@link #returnToService(UnitOfWork, String, Instant, String, String, String, int)} after a repair. */
    public static MaintenanceRecord returnToService(UnitOfWork unit, String board, Instant at, String by) {
        return returnToService(unit, board, at, by, REPAIR, "", 0);
    }

    /**
     * Log the visit and put the board back, in one unit of work.
     *
     * <p>The state move is the point. A board left in maintenance because somebody finished the work
     * and forgot the second command is the most common way a board disappears off the bench for a
     * week. The move gives the notes as its reason, or says which kind of visit finished.
     *
     * <p>A board already available is left where it is rather than moved to where it already is,
     * because logging a calibration on a board nobody had taken out of service is an ordinary thing
     * to do and should not be an error.
     */
    public static MaintenanceRecord returnToService(
            UnitOfWork unit, String board, Instant at, String by, String kind, String notes, int faultsCleared) {
        MaintenanceRecord record = logVisit(unit, board, kind, at, by, notes, faultsCleared);
        if (unit.boards().state(board).state() != BoardStatus.AVAILABLE) {
            String reason = notes.isEmpty() ? kind + " finished" : notes;
            Inventory.moveState(unit, board, BoardStatus.AVAILABLE, at, reason, by);
        }
        return record;
    }

    private static String knownKinds() {
        return Arrays.stream(MaintenanceKind.values())
                .map(MaintenanceKind::value)
                .sorted()
                .collect(Collectors.joining(", "));
    }
}
