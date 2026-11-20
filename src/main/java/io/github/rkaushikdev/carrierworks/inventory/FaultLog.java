package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Every fault the bench has seen, in the order it saw them.
 *
 * <p>Every board fails sometimes. A cable gets nudged, a rail sags while somebody is plugging in a
 * supply on the shelf below, a verification read comes back wrong once and right the next four
 * times. Treating each of those as a reason to pull a board would empty the rack in a week, so
 * faults are recorded here rather than acted on, and a {@link QuarantinePolicy} decides when the
 * pattern is worth something.
 */
public final class FaultLog {

    private final List<FaultRecord> records;

    /** A log with nothing in it yet. */
    public FaultLog() {
        this(List.of());
    }

    /** A log that already holds these faults, in this order. */
    public FaultLog(Collection<FaultRecord> records) {
        this.records = new ArrayList<>(records);
    }

    /** Write a fault down and hand it back. */
    public FaultRecord record(FaultRecord fault) {
        records.add(fault);
        return fault;
    }

    /** Write down a fault with nothing more to say about it. */
    public FaultRecord add(String board, FaultKind kind, Instant at) {
        return record(new FaultRecord(board, kind, at));
    }

    /** Write down a fault, what was seen and the job that was running. */
    public FaultRecord add(String board, FaultKind kind, Instant at, String detail, String job) {
        return record(new FaultRecord(board, kind, at, detail, job));
    }

    /** Every fault, oldest first. */
    public List<FaultRecord> all() {
        return List.copyOf(records);
    }

    /** One board's faults, oldest first. */
    public List<FaultRecord> forBoard(String board) {
        String tag = Records.boardKey(board);
        return records.stream().filter(record -> record.board().equals(tag)).toList();
    }

    /**
     * Faults on a board inside a window ending now, ends included.
     *
     * @throws ValidationError when the window has no length
     */
    public List<FaultRecord> within(String board, Instant now, Duration window) {
        return Records.inWindow(forBoard(board), now, window, FaultRecord::at);
    }

    /** Faults of one kind on a board inside a window ending now. */
    public List<FaultRecord> within(String board, Instant now, Duration window, FaultKind kind) {
        return within(board, now, window).stream()
                .filter(record -> record.kind() == kind)
                .toList();
    }

    /** How many faults a board had inside a window ending now. */
    public int count(String board, Instant now, Duration window) {
        return within(board, now, window).size();
    }

    /** How many faults of one kind a board had inside a window ending now. */
    public int count(String board, Instant now, Duration window, FaultKind kind) {
        return within(board, now, window, kind).size();
    }

    /** The most recent fault on a board, if it has had one. */
    public Optional<FaultRecord> last(String board) {
        List<FaultRecord> found = forBoard(board);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getLast());
    }

    /** Every board with a fault on record, sorted. */
    public List<String> boards() {
        return List.copyOf(
                new TreeSet<>(records.stream().map(FaultRecord::board).toList()));
    }

    /**
     * Forget a board's faults, which is what maintenance does.
     *
     * @return how many were dropped, because that number goes in the maintenance record and somebody
     *     will want it later
     */
    public int clear(String board) {
        String tag = Records.boardKey(board);
        int before = records.size();
        records.removeIf(record -> record.board().equals(tag));
        return before - records.size();
    }
}
