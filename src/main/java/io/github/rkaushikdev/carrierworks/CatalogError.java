package io.github.rkaushikdev.carrierworks;

import java.util.Map;

/** The device, flash or card catalog disagrees with itself. */
public class CatalogError extends ConfigurationError {

    public CatalogError(String message) {
        super(message);
    }

    public CatalogError(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "catalog";
    }
}
