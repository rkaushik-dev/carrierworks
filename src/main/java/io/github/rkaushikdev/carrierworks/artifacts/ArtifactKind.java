package io.github.rkaushikdev.carrierworks.artifacts;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;

/**
 * Whether a stored image configures the whole device or only rewrites some frames of a running
 * one.
 */
public enum ArtifactKind {
    /** Configures the whole device and ends by starting it. */
    FULL("full"),
    /** Rewrites some frames while the device carries on running. */
    PARTIAL("partial");

    private final String value;

    ArtifactKind(String value) {
        this.value = value;
    }

    /** The name the kind is stored and printed under. */
    public String value() {
        return value;
    }

    /** The kind with exactly this name. */
    public static ArtifactKind parse(String text) {
        for (ArtifactKind kind : values()) {
            if (kind.value.equals(text)) {
                return kind;
            }
        }
        throw new ValidationError("an image is full or partial", "kind", Details.of("given", text));
    }

    @Override
    public String toString() {
        return value;
    }
}
