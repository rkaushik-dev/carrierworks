package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.jobs.JobMode;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;

/**
 * A programming job being asked for.
 *
 * @param board the board to program
 * @param digest the image to program it with
 * @param mode how, a JTAG load when left out
 * @param requestedBy who is asking
 * @param verify whether to read the board back afterwards, yes when left out
 */
public record JobIn(
        @NotNull(message = Errors.REQUIRED) String board,
        @NotNull(message = Errors.REQUIRED) String digest,
        String mode,
        @NotNull(message = Errors.REQUIRED) String requestedBy,
        Boolean verify) {

    public JobIn {
        mode = Objects.requireNonNullElse(mode, JobMode.JTAG.value());
        verify = Objects.requireNonNullElse(verify, true);
    }
}
