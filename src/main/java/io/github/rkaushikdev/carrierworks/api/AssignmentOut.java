package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.services.DispatchOutcome;

/**
 * One request that got a board.
 *
 * @param request the request served
 * @param board the board it was given
 * @param lease the lease taken for it
 */
public record AssignmentOut(String request, String board, String lease) {

    /** One assignment a pass made. */
    public static AssignmentOut of(DispatchOutcome.Assigned assigned) {
        return new AssignmentOut(assigned.request(), assigned.board(), assigned.lease());
    }
}
