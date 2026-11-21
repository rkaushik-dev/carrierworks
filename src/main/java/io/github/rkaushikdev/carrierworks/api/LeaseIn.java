package io.github.rkaushikdev.carrierworks.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * A request to take a board.
 *
 * @param holder who is taking it
 * @param purpose what for
 * @param minutes how long, or the site's default length when left out
 * @param priority how urgent, {@value Lease#DEFAULT_PRIORITY} when left out
 * @param preemptible whether something more urgent may take it away, yes when left out
 */
public record LeaseIn(
        @NotNull(message = Errors.REQUIRED) String holder,
        @NotNull(message = Errors.REQUIRED) String purpose,
        OptionalInt minutes,
        @JsonDeserialize(using = Defaulted.class) Integer priority,
        @JsonDeserialize(using = Defaulted.class) Boolean preemptible) {

    public LeaseIn {
        minutes = Objects.requireNonNullElse(minutes, OptionalInt.empty());
        priority = Objects.requireNonNullElse(priority, Lease.DEFAULT_PRIORITY);
        preemptible = Objects.requireNonNullElse(preemptible, true);
    }
}
