package io.github.rkaushikdev.carrierworks;

import java.util.Map;

/** The bench was described wrong, in a file or a database row. */
public class ConfigurationError extends BenchError {

    public ConfigurationError(String message) {
        super(message);
    }

    public ConfigurationError(String message, Map<String, ?> details) {
        super(message, details);
    }

    public ConfigurationError(String message, Map<String, ?> details, Throwable cause) {
        super(message, details, cause);
    }

    @Override
    public String code() {
        return "configuration";
    }
}
