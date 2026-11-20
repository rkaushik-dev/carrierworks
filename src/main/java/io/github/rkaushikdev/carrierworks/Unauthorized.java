package io.github.rkaushikdev.carrierworks;

import java.util.Map;

/**
 * No credential was presented, or the one presented is not a key.
 *
 * <p>Separate from {@link Forbidden} because the remedy is different: this one means send a key,
 * and that one means send a different key or ask somebody for a role.
 */
public class Unauthorized extends BenchError {

    public Unauthorized(String message) {
        super(message);
    }

    public Unauthorized(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "unauthorized";
    }
}
