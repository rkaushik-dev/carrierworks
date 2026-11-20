package io.github.rkaushikdev.carrierworks;

import java.util.Map;

/**
 * A value handed in from outside does not hold up.
 *
 * <p>Most refusals name the field they are about, and the name goes into the details beside
 * whatever else the refusal carries.
 */
public class ValidationError extends BenchError {

    public ValidationError(String message) {
        super(message);
    }

    /** A refusal about one named field. */
    public ValidationError(String message, String field) {
        this(message, field, Map.of());
    }

    /** A refusal about no field in particular, with the specifics behind it. */
    public ValidationError(String message, Map<String, ?> details) {
        super(message, details);
    }

    /** A refusal about one named field, with the specifics behind it. */
    public ValidationError(String message, String field, Map<String, ?> details) {
        super(message, plus(details, "field", field));
    }

    @Override
    public String code() {
        return "validation";
    }
}
