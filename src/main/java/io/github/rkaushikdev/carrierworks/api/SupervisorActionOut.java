package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.thermal.SupervisorAction;
import java.util.List;

/**
 * Something the supervisor did on a pass.
 *
 * @param board the board it is about
 * @param action what it did: note, watch, warn or shutdown
 * @param reason why, in words for the log
 * @param affected the boards downwind of this one, nearest first, when that matters
 */
public record SupervisorActionOut(String board, String action, String reason, List<String> affected) {

    public SupervisorActionOut {
        affected = List.copyOf(affected);
    }

    /** One action. */
    public static SupervisorActionOut of(SupervisorAction action) {
        return new SupervisorActionOut(action.board(), action.action().value(), action.reason(), action.affected());
    }
}
