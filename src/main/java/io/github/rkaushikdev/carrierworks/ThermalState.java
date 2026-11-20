package io.github.rkaushikdev.carrierworks;

import java.util.List;

/**
 * How hot a board is, as one of the bands the bench escalates through.
 *
 * <p>{@link #NORMAL} to {@link #SHUTDOWN} are ordered coldest to hottest, and the order is the
 * escalation. {@link #UNKNOWN} sits outside it: a board that has stopped reporting is in a state
 * the bench cannot reason about, and the honest thing is to say so and let the supervisor decide.
 */
public enum ThermalState {
    NORMAL("normal"),
    WARN("warn"),
    CRITICAL("critical"),
    SHUTDOWN("shutdown"),
    UNKNOWN("unknown");

    /** The four bands a reading can put a board in, coldest first. */
    public static final List<ThermalState> ESCALATION = List.of(NORMAL, WARN, CRITICAL, SHUTDOWN);

    private final String value;

    ThermalState(String value) {
        this.value = value;
    }

    /** The name the state is stored and printed under. */
    public String value() {
        return value;
    }

    /** Where this band sits in the escalation, coldest first; unknown has no place in it. */
    public int level() {
        int level = ESCALATION.indexOf(this);
        if (level < 0) {
            throw new ValidationError("unknown thermal state", "state", Details.of("given", value));
        }
        return level;
    }

    /** The state with this stored name. */
    public static ThermalState parse(String text) {
        for (ThermalState state : values()) {
            if (state.value.equals(text)) {
                return state;
            }
        }
        throw new ValidationError("no such thermal state", "state", Details.of("given", text));
    }

    @Override
    public String toString() {
        return value;
    }
}
