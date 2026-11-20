package io.github.rkaushikdev.carrierworks.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.exception.ConstraintViolationException;

/** Asserting that the database itself refused a write. */
final class Constraints {

    private Constraints() {}

    /** The work fails, and somewhere in why is a constraint the schema declares. */
    static void refused(ThrowingCallable work) {
        Throwable thrown = catchThrowable(work);
        assertThat(thrown).as("the write should have been refused").isNotNull();
        boolean violation = false;
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            violation |= cause instanceof ConstraintViolationException;
        }
        assertThat(violation).as("refused by a constraint, not by %s", thrown).isTrue();
    }
}
