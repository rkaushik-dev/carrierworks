package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.scheduling.Request;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Objects;

/**
 * Somebody asking for a board they have not got yet.
 *
 * @param holder who is asking
 * @param purpose what for
 * @param needs what the board has to do, separated by commas, nothing when left out
 * @param priority how urgent, {@value Lease#MIN_PRIORITY} to {@value Lease#MAX_PRIORITY}, {@value
 *     Lease#DEFAULT_PRIORITY} when left out
 * @param minutes how long the board is wanted for, {@value Request#DEFAULT_MINUTES} when left out
 * @param preemptible whether the lease may be taken away by something more urgent, yes when left
 *     out
 * @param needsHands whether the job needs somebody in the lab, no when left out
 */
public record RequestIn(
        @NotNull(message = Errors.REQUIRED) String holder,
        @NotNull(message = Errors.REQUIRED) String purpose,
        String needs,
        @Min(Lease.MIN_PRIORITY) @Max(Lease.MAX_PRIORITY) Integer priority,
        @Positive Integer minutes,
        Boolean preemptible,
        Boolean needsHands) {

    public RequestIn {
        needs = Objects.requireNonNullElse(needs, "");
        priority = Objects.requireNonNullElse(priority, Lease.DEFAULT_PRIORITY);
        minutes = Objects.requireNonNullElse(minutes, Request.DEFAULT_MINUTES);
        preemptible = Objects.requireNonNullElse(preemptible, true);
        needsHands = Objects.requireNonNullElse(needsHands, false);
    }
}
