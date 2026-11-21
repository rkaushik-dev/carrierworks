package io.github.rkaushikdev.carrierworks.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A board arriving from a config file or a person.
 *
 * <p>Everything crosses the wire as the string people write it as: an asset tag is {@code bx-14},
 * a location is {@code rack-2/shelf-04/slot-3}, a part is an ordering code. The domain reads each
 * of those itself when the board is built, so there is exactly one parser for each thing and this
 * shape only says which fields have to be there. What a field left out is given is said on each.
 *
 * @param asset the asset tag
 * @param model the carrier model
 * @param part the device's ordering code
 * @param flash the configuration flash part
 * @param location where it is racked
 * @param revision the carrier revision, {@value Board#DEFAULT_REVISION} when left out
 * @param slots its connectors, none when left out
 * @param adapterSerial the serial of the cable hanging off it, if any
 * @param adapterModel the model of that cable, if any; a cable needs both
 * @param pdu the outlet feeding it, such as {@code pdu-c/outlet-04}, if known
 * @param vadjMillivolts what the adjustable rail is strapped to, {@value Board#DEFAULT_MILLIVOLTS}
 *     when left out
 * @param headerVoltageMv the JTAG header's level, {@value Board#DEFAULT_MILLIVOLTS} when left out
 * @param maxTckHz the fastest the header is rated for, {@value Board#DEFAULT_MAX_TCK_HZ} when left
 *     out
 * @param notes anything else worth knowing, nothing when left out
 */
public record BoardIn(
        @NotNull(message = Errors.REQUIRED) String asset,
        @NotNull(message = Errors.REQUIRED) String model,
        @NotNull(message = Errors.REQUIRED) String part,
        @NotNull(message = Errors.REQUIRED) String flash,
        @NotNull(message = Errors.REQUIRED) String location,
        @JsonDeserialize(using = Defaulted.class) String revision,
        @JsonDeserialize(using = Defaulted.class) @Valid List<SlotIn> slots,
        Optional<String> adapterSerial,
        Optional<String> adapterModel,
        Optional<String> pdu,
        @JsonDeserialize(using = Defaulted.class) Integer vadjMillivolts,
        @JsonDeserialize(using = Defaulted.class) Integer headerVoltageMv,
        @JsonDeserialize(using = Defaulted.class) Integer maxTckHz,
        @JsonDeserialize(using = Defaulted.class) String notes) {

    public BoardIn {
        revision = Objects.requireNonNullElse(revision, Board.DEFAULT_REVISION);
        slots = slots == null ? List.of() : List.copyOf(slots);
        adapterSerial = Objects.requireNonNullElse(adapterSerial, Optional.empty());
        adapterModel = Objects.requireNonNullElse(adapterModel, Optional.empty());
        pdu = Objects.requireNonNullElse(pdu, Optional.empty());
        vadjMillivolts = Objects.requireNonNullElse(vadjMillivolts, Board.DEFAULT_MILLIVOLTS);
        headerVoltageMv = Objects.requireNonNullElse(headerVoltageMv, Board.DEFAULT_MILLIVOLTS);
        maxTckHz = Objects.requireNonNullElse(maxTckHz, Board.DEFAULT_MAX_TCK_HZ);
        notes = Objects.requireNonNullElse(notes, "");
    }
}
