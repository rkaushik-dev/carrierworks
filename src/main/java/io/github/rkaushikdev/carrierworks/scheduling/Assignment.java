package io.github.rkaushikdev.carrierworks.scheduling;

import io.github.rkaushikdev.carrierworks.leases.Lease;
import java.util.Optional;

/**
 * One request that got a board.
 *
 * @param request the request id
 * @param board the board it got
 * @param lease the lease it holds the board by
 * @param preempted the lease that was closed to free the board, if one was
 */
public record Assignment(String request, String board, Lease lease, Optional<Lease> preempted) {

    /** A request served from a board that was free. */
    public Assignment(String request, String board, Lease lease) {
        this(request, board, lease, Optional.empty());
    }

    /** {@code req-00002 got bx-14 on lease-00002}, adding {@code , taken from team-a} after a preemption. */
    public String describe() {
        String line = request + " got " + board + " on " + lease.id();
        return preempted.map(taken -> line + ", taken from " + taken.holder()).orElse(line);
    }
}
