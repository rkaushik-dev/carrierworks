package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Arrays;

/**
 * What was done to a board while it was out of service.
 *
 * <p>A repair and a calibration are the same act from the bench's point of view: somebody took the
 * board out, did something to it, and put it back. They differ in why they happened and in what
 * they mean afterwards.
 */
public enum MaintenanceKind {
    REPAIR("repair"),
    CALIBRATION("calibration"),
    INSPECTION("inspection"),
    UPGRADE("upgrade");

    private final String value;

    MaintenanceKind(String value) {
        this.value = value;
    }

    /** The name the kind is stored, printed and asked for under. */
    public String value() {
        return value;
    }

    /**
     * The kind with this name, however it was written.
     *
     * @throws ValidationError naming the normalised text when no kind has that name
     */
    public static MaintenanceKind parse(String text) {
        String name = Ids.slug(text);
        return Arrays.stream(values())
                .filter(kind -> kind.value.equals(name))
                .findFirst()
                .orElseThrow(() -> new ValidationError("no such maintenance kind", "kind", Details.of("given", name)));
    }

    @Override
    public String toString() {
        return value;
    }
}
