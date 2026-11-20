package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.catalog.CardSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * What has been done to each board, and what is due.
 *
 * <p>Calibration is where the interesting rule is. A bare carrier does not need calibrating,
 * because there is nothing on it whose readings anybody trusts. A carrier with a converter card on
 * it does, annually, because the results of a capture run are only meaningful if somebody has
 * checked the front end against a reference in the last year. So the interval is not a property of
 * the board, it is a property of what is fitted to it today, and fitting a card to a board that has
 * never been calibrated makes it immediately due.
 */
public final class MaintenanceBook {

    /** How often anything measuring an analogue signal is calibrated: once a year. */
    public static final Duration CALIBRATION_INTERVAL = Duration.ofDays(365);

    private static final List<String> MEASURING = List.of("adc-", "dac-");

    private final List<MaintenanceRecord> records;

    /** A book with nothing in it yet. */
    public MaintenanceBook() {
        this(List.of());
    }

    /** A book that already holds these visits, in this order. */
    public MaintenanceBook(Collection<MaintenanceRecord> records) {
        this.records = new ArrayList<>(records);
    }

    /**
     * How often this board needs calibrating, or nothing if it does not.
     *
     * <p>Read off the cards fitted right now rather than off the board, so removing the converter
     * takes the requirement away with it.
     */
    public static Optional<Duration> calibrationInterval(Board board) {
        for (CardSpec spec : board.fittedCards()) {
            boolean measures = spec.provides().stream()
                    .anyMatch(capability -> MEASURING.stream().anyMatch(capability::startsWith));
            if (measures) {
                return Optional.of(CALIBRATION_INTERVAL);
            }
        }
        return Optional.empty();
    }

    /** Write a visit down and hand it back. */
    public MaintenanceRecord record(MaintenanceRecord entry) {
        records.add(entry);
        return entry;
    }

    /** Write down a visit with no notes that cleared nothing. */
    public MaintenanceRecord add(String board, MaintenanceKind kind, Instant at, String by) {
        return record(new MaintenanceRecord(board, kind, at, by));
    }

    /** Write down a visit with its notes and the faults it cleared. */
    public MaintenanceRecord add(
            String board, MaintenanceKind kind, Instant at, String by, String notes, int faultsCleared) {
        return record(new MaintenanceRecord(board, kind, at, by, notes, faultsCleared));
    }

    /** Every visit, in the order written down. */
    public List<MaintenanceRecord> all() {
        return List.copyOf(records);
    }

    /** One board's visits, in the order written down. */
    public List<MaintenanceRecord> forBoard(String board) {
        String tag = Records.boardKey(board);
        return records.stream().filter(record -> record.board().equals(tag)).toList();
    }

    /** The latest visit to a board of any kind; of two at the same moment, the one written first. */
    public Optional<MaintenanceRecord> last(String board) {
        return latest(forBoard(board));
    }

    /** The latest visit to a board of one kind. */
    public Optional<MaintenanceRecord> last(String board, MaintenanceKind kind) {
        return latest(
                forBoard(board).stream().filter(record -> record.kind() == kind).toList());
    }

    /** When the board was last calibrated, if ever. */
    public Optional<Instant> calibratedAt(String board) {
        return last(board, MaintenanceKind.CALIBRATION).map(MaintenanceRecord::at);
    }

    /**
     * When this board's calibration runs out, or nothing if it needs none.
     *
     * <p>A board that needs calibration and has never had any is due now, which is the honest
     * answer rather than a special case.
     */
    public Optional<Instant> calibrationDue(Board board, Instant now) {
        return calibrationInterval(board).map(interval -> calibratedAt(board.tag())
                .map(last -> last.plus(interval))
                .orElse(now));
    }

    /** Whether the board needs no calibration or its last one has not yet run out. */
    public boolean calibrationValid(Board board, Instant now) {
        return calibrationDue(board, now).map(due -> due.isAfter(now)).orElse(true);
    }

    /**
     * Every board whose calibration has run out, soonest expired first.
     *
     * <p>Boards never calibrated sort as of now, after every board that was; ties keep the order
     * they were given in.
     */
    public List<Board> overdue(Collection<Board> boards, Instant now) {
        List<Board> late = new ArrayList<>(
                boards.stream().filter(board -> !calibrationValid(board, now)).toList());
        late.sort(Comparator.comparing(board -> calibratedAt(board.tag()).orElse(now)));
        return List.copyOf(late);
    }

    /** How many faults every visit to this board has cleared between them. */
    public int faultsClearedFor(String board) {
        return forBoard(board).stream()
                .mapToInt(MaintenanceRecord::faultsCleared)
                .sum();
    }

    /** Every board with a visit on record, sorted. */
    public List<String> boards() {
        return List.copyOf(
                new TreeSet<>(records.stream().map(MaintenanceRecord::board).toList()));
    }

    private static Optional<MaintenanceRecord> latest(List<MaintenanceRecord> found) {
        return found.stream().max(Comparator.comparing(MaintenanceRecord::at));
    }
}
