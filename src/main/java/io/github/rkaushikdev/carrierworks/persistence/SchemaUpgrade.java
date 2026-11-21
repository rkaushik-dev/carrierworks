package io.github.rkaushikdev.carrierworks.persistence;

import java.util.Objects;
import java.util.Optional;

/**
 * What bringing a database's schema up to date did.
 *
 * @param from the version the schema was at, or empty for a database with no schema yet
 * @param to the version it is at now, which is the newest there is
 * @param applied how many migrations that took, none when it was already up to date
 */
public record SchemaUpgrade(Optional<String> from, String to, int applied) {

    public SchemaUpgrade {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    /** One line for whoever ran it. */
    public String describe() {
        if (applied == 0) {
            return "the schema is already at version " + to;
        }
        String took = applied + (applied == 1 ? " migration" : " migrations");
        return from.map(older -> "schema moved from version " + older + " to " + to + ", " + took)
                .orElse("schema built at version " + to + ", " + took);
    }
}
