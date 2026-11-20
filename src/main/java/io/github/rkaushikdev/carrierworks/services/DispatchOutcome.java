package io.github.rkaushikdev.carrierworks.services;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one pass over the waiting list did, and what it could not do.
 *
 * @param assigned every request that got a board, in the order they were served
 * @param refusals why each request looked at and not served is still waiting, by request id, in
 *     the order they were looked at
 * @param preempted every lease taken off its holder to serve a request, in the order they were taken
 */
public record DispatchOutcome(List<Assigned> assigned, Map<String, String> refusals, List<Preempted> preempted) {

    /**
     * One request that got a board.
     *
     * @param request the request's id
     * @param board the board it got
     * @param lease the id of the lease it holds the board by
     */
    public record Assigned(String request, String board, String lease) {}

    /**
     * One lease taken off its holder.
     *
     * @param lease the id of the lease that was closed
     * @param board the board it held
     */
    public record Preempted(String lease, String board) {}

    public DispatchOutcome {
        assigned = List.copyOf(assigned);
        refusals = Collections.unmodifiableMap(new LinkedHashMap<>(refusals));
        preempted = List.copyOf(preempted);
    }

    /**
     * {@code req-0001 -> bx-14 as lease-00002; took lease-00001 off bx-14; req-0002 waits: ...},
     * refusals in request order, or {@code nothing waiting} when the pass looked at nothing.
     */
    public String describe() {
        if (assigned.isEmpty() && refusals.isEmpty()) {
            return "nothing waiting";
        }
        List<String> lines = new ArrayList<>();
        assigned.forEach(served -> lines.add(served.request() + " -> " + served.board() + " as " + served.lease()));
        preempted.forEach(taken -> lines.add("took " + taken.lease() + " off " + taken.board()));
        new TreeMap<>(refusals).forEach((request, why) -> lines.add(request + " waits: " + why));
        return String.join("; ", lines);
    }
}
