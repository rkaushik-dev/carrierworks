package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.services.DispatchOutcome;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one pass over the line did.
 *
 * @param assigned the requests that got a board, in the order they were served
 * @param waiting why each request still waiting did not get one, by request id, in line order
 */
public record DispatchOut(List<AssignmentOut> assigned, Map<String, String> waiting) {

    public DispatchOut {
        assigned = List.copyOf(assigned);
        waiting = Collections.unmodifiableMap(new LinkedHashMap<>(waiting));
    }

    /** One pass. */
    public static DispatchOut of(DispatchOutcome outcome) {
        return new DispatchOut(
                outcome.assigned().stream().map(AssignmentOut::of).toList(), outcome.refusals());
    }
}
