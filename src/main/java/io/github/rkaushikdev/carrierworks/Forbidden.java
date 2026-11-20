package io.github.rkaushikdev.carrierworks;

import java.util.LinkedHashMap;
import java.util.Map;

/** A key was presented and it does not carry the role this needs. */
public class Forbidden extends BenchError {

    public Forbidden(String message) {
        super(message);
    }

    /** A refusal naming the role needed and the one held; an empty name is left out. */
    public Forbidden(String message, String needs, String holds) {
        this(message, needs, holds, Map.of());
    }

    public Forbidden(String message, String needs, String holds, Map<String, ?> details) {
        super(message, roles(needs, holds, details));
    }

    private static Map<String, Object> roles(String needs, String holds, Map<String, ?> details) {
        Map<String, Object> merged = new LinkedHashMap<>(details);
        if (!needs.isEmpty()) {
            merged.put("needs", needs);
        }
        if (!holds.isEmpty()) {
            merged.put("holds", holds);
        }
        return merged;
    }

    @Override
    public String code() {
        return "forbidden";
    }
}
