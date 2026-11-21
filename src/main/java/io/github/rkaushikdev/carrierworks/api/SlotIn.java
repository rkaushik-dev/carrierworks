package io.github.rkaushikdev.carrierworks.api;

import jakarta.validation.constraints.NotNull;
import java.util.Objects;
import java.util.Optional;

/**
 * One connector on a board being written down.
 *
 * @param name the connector's name on the board, such as {@code fmc0}
 * @param connector the kind of connector, such as {@code fmc-hpc}
 * @param fitted the card in it, if there is one
 */
public record SlotIn(
        @NotNull(message = Errors.REQUIRED) String name,
        @NotNull(message = Errors.REQUIRED) String connector,
        Optional<String> fitted) {

    public SlotIn {
        fitted = Objects.requireNonNullElse(fitted, Optional.empty());
    }
}
