package io.github.rkaushikdev.carrierworks.scheduling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What one tick of the scheduler did.
 *
 * @param assignments every request that got a board, in the order they were served
 * @param refusals why each request that was looked at and not served stayed on the queue, by
 *     request id, in the order they were looked at
 */
public record DispatchResult(List<Assignment> assignments, Map<String, String> refusals) {

    public DispatchResult {
        assignments = List.copyOf(assignments);
        refusals = Collections.unmodifiableMap(new LinkedHashMap<>(refusals));
    }

    /** How many requests got a board. */
    public int served() {
        return assignments.size();
    }

    /** Why a request was not served on this tick, if it was looked at and refused. */
    public Optional<String> reasonFor(String requestId) {
        return Optional.ofNullable(refusals.get(requestId));
    }

    /**
     * Every assignment, then every refusal as {@code req-00002: reason} in request id order, joined
     * with semicolons; {@code nothing waiting} when the tick looked at nothing.
     */
    public String describe() {
        if (assignments.isEmpty() && refusals.isEmpty()) {
            return "nothing waiting";
        }
        List<String> parts = new ArrayList<>();
        assignments.forEach(assignment -> parts.add(assignment.describe()));
        new TreeMap<>(refusals).forEach((request, reason) -> parts.add(request + ": " + reason));
        return String.join("; ", parts);
    }
}
