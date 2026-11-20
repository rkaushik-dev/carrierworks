package io.github.rkaushikdev.carrierworks.flash;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;

/** What a stretch of the configuration flash is kept for. */
public enum RegionPurpose {
    /** The image the device loads after power on, at address zero. */
    GOLDEN("golden"),
    /** The image a multiboot jump loads instead. */
    UPDATE("update"),
    /** Anything else a design keeps in the part. */
    DATA("data"),
    /** Space set aside and left alone. */
    RESERVED("reserved");

    private final String value;

    RegionPurpose(String value) {
        this.value = value;
    }

    /** The name the purpose is printed under. */
    public String value() {
        return value;
    }

    /** The purpose with exactly this name. */
    public static RegionPurpose parse(String text) {
        for (RegionPurpose purpose : values()) {
            if (purpose.value.equals(text)) {
                return purpose;
            }
        }
        throw new ValidationError("no such region purpose", "purpose", Details.of("given", text));
    }

    @Override
    public String toString() {
        return value;
    }
}
