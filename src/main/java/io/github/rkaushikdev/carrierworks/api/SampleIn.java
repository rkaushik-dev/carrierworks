package io.github.rkaushikdev.carrierworks.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.github.rkaushikdev.carrierworks.thermal.ThermalSample;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One reading a rack controller is posting.
 *
 * <p>The time is optional because the common case is a controller posting what it just read, and
 * the server clock is the one everything else on the bench is stamped with. A controller replaying
 * a backlog sends its own times.
 *
 * @param board the board it was read off
 * @param millidegrees the temperature, in thousandths of a degree Celsius
 * @param at when it was read, or the moment it arrives when left out
 * @param source which sensor it came off, {@value ThermalSample#DEFAULT_SOURCE} when left out
 */
public record SampleIn(
        @NotNull(message = Errors.REQUIRED) String board,
        @NotNull(message = Errors.REQUIRED) Integer millidegrees,
        Optional<Instant> at,
        @JsonDeserialize(using = Defaulted.class) String source) {

    public SampleIn {
        at = Objects.requireNonNullElse(at, Optional.empty());
        source = Objects.requireNonNullElse(source, ThermalSample.DEFAULT_SOURCE);
    }
}
