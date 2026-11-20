package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Instant;

/**
 * One thing that went wrong on one board.
 *
 * @param board the asset tag, normalised
 * @param kind what went wrong
 * @param at when it happened
 * @param detail what was seen, or empty
 * @param job the job that was running, or empty
 */
public record FaultRecord(String board, FaultKind kind, Instant at, String detail, String job) {

    public FaultRecord {
        board = Records.boardKey(board);
    }

    /** A fault with nothing more to say about it and no job behind it. */
    public FaultRecord(String board, FaultKind kind, Instant at) {
        this(board, kind, at, "", "");
    }

    /** {@code bx-14 chain at 2025-07-14T18:00:00+00:00: all ones [job-1]}. */
    public String describe() {
        String line = board + " " + kind + " at " + Times.iso(at, Times.Precision.SECONDS);
        if (!detail.isEmpty()) {
            line = line + ": " + detail;
        }
        if (!job.isEmpty()) {
            line = line + " [" + job + "]";
        }
        return line;
    }
}
