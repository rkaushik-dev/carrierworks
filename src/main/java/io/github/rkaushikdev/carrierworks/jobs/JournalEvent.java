package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Arrays;

/**
 * What a journal line says happened to a job.
 *
 * <p>Started and step leave a job open; passed, failed, cancelled and abandoned close it. Created
 * does neither, because a job that was only ever queued has not touched a board.
 */
public enum JournalEvent {
    CREATED("created"),
    STARTED("started"),
    STEP("step"),
    PASSED("passed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ABANDONED("abandoned");

    private final String value;

    JournalEvent(String value) {
        this.value = value;
    }

    /** The name the event is written to the journal under. */
    public String value() {
        return value;
    }

    /** Whether a job is over once this is written. */
    public boolean closes() {
        return this == PASSED || this == FAILED || this == CANCELLED || this == ABANDONED;
    }

    /** Whether a job is part way through once this is written. */
    boolean leavesOpen() {
        return this == STARTED || this == STEP;
    }

    /** The event with this name, however it was written. */
    public static JournalEvent parse(String text) {
        String name = Ids.slug(text);
        return Arrays.stream(values())
                .filter(event -> event.value.equals(name))
                .findFirst()
                .orElseThrow(() -> new ValidationError("no such journal event", "event", Details.of("given", name)));
    }

    @Override
    public String toString() {
        return value;
    }
}
