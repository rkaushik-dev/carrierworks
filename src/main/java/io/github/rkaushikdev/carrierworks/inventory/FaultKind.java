package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Arrays;

/**
 * What sort of thing went wrong on a board.
 *
 * <p>The kinds matter to the quarantine policy: a chain or power fault comes and goes, while a
 * verify failure means the flash wrote something other than what was sent.
 */
public enum FaultKind {
    CHAIN("chain"),
    PROGRAM("program"),
    VERIFY("verify"),
    THERMAL("thermal"),
    POWER("power"),
    OTHER("other");

    private final String value;

    FaultKind(String value) {
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
    public static FaultKind parse(String text) {
        String name = Ids.slug(text);
        return Arrays.stream(values())
                .filter(kind -> kind.value.equals(name))
                .findFirst()
                .orElseThrow(() -> new ValidationError("no such fault kind", "kind", Details.of("given", name)));
    }

    @Override
    public String toString() {
        return value;
    }
}
