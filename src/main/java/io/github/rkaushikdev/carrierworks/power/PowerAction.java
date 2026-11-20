package io.github.rkaushikdev.carrierworks.power;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Arrays;

/** The four things a power sequence does to a rail. */
public enum PowerAction {
    /** Tell the rail's regulator to start. */
    ENABLE("enable"),
    /** Tell it to stop. */
    DISABLE("disable"),
    /** Wait for the rail to come up or down. */
    SETTLE("settle"),
    /** Read the rail back and make sure it is inside its window. */
    CHECK("check");

    private final String value;

    PowerAction(String value) {
        this.value = value;
    }

    /** The name the action is printed and asked for under. */
    public String value() {
        return value;
    }

    /**
     * The action with exactly this name.
     *
     * @throws ValidationError naming the text when no action has that name
     */
    public static PowerAction parse(String text) {
        return Arrays.stream(values())
                .filter(action -> action.value.equals(text))
                .findFirst()
                .orElseThrow(() -> new ValidationError("no such power step", "action", Details.of("given", text)));
    }

    @Override
    public String toString() {
        return value;
    }
}
