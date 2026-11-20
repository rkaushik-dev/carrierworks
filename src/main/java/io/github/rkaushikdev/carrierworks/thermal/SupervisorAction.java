package io.github.rkaushikdev.carrierworks.thermal;

import java.util.List;

/**
 * Something the supervisor did, or would have done.
 *
 * @param board the board it is about
 * @param action what the supervisor did
 * @param reason why, in words for the log
 * @param affected the boards downwind of this one, nearest first, when that matters
 */
public record SupervisorAction(String board, Kind action, String reason, List<String> affected) {

    public SupervisorAction {
        affected = List.copyOf(affected);
    }

    /** An action that names no other boards. */
    public SupervisorAction(String board, Kind action, String reason) {
        this(board, action, reason, List.of());
    }

    /** {@code bx-14 warn: bx-14 critical at 88.0C (downwind: bx-15)}, without the brackets when nothing is downwind. */
    public String describe() {
        String line = board + " " + action.value() + ": " + reason;
        if (affected.isEmpty()) {
            return line;
        }
        return line + " (downwind: " + String.join(", ", affected) + ")";
    }

    /** What the supervisor can do about a board, mildest first. */
    public enum Kind {
        /** A warm board, written down and otherwise left alone. */
        NOTE("note"),
        /** A board in use that has gone quiet, still inside its grace. */
        WATCH("watch"),
        /** A critical board, with the boards in its warm air named. */
        WARN("warn"),
        /** A board powered off, its lease given back, and taken out of service. */
        SHUTDOWN("shutdown");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        /** The name the action is stored and printed under. */
        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
