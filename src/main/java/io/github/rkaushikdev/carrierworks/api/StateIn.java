package io.github.rkaushikdev.carrierworks.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;

/**
 * A state change somebody is asking for.
 *
 * @param state the state the board should move to, as it is written
 * @param reason why, nothing when left out; the out of service states insist on one
 * @param actor who is asking, nothing when left out
 */
public record StateIn(
        @NotNull(message = Errors.REQUIRED) String state,
        @JsonDeserialize(using = Defaulted.class) String reason,
        @JsonDeserialize(using = Defaulted.class) String actor) {

    public StateIn {
        reason = Objects.requireNonNullElse(reason, "");
        actor = Objects.requireNonNullElse(actor, "");
    }
}
