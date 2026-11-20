package io.github.rkaushikdev.carrierworks.power;

import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.RailSpec;
import java.util.List;

/**
 * One rail in a power tree, with what has to be up first and how long it takes to settle.
 *
 * @param rail the rail and the window it has to hold
 * @param dependsOn the rails that have to be up before this one, as slugs
 * @param settleMs how long the rail takes to settle once enabled
 * @param downFirst whether the rail comes down ahead of the reverse order, as the analogue
 *     transceiver supplies must
 */
public record RailStep(RailSpec rail, List<String> dependsOn, int settleMs, boolean downFirst) {

    /** How long a rail settles unless its datasheet says otherwise. */
    public static final int DEFAULT_SETTLE_MS = 5;

    public RailStep {
        dependsOn = dependsOn.stream().map(Ids::slug).toList();
        if (settleMs < 0) {
            throw new ConfigurationError("a rail cannot settle in negative time", Details.of("rail", rail.name()));
        }
    }

    /** A rail that needs nothing before it and settles in the usual time. */
    public RailStep(RailSpec rail) {
        this(rail, List.of());
    }

    /** A rail that settles in the usual time. */
    public RailStep(RailSpec rail, List<String> dependsOn) {
        this(rail, dependsOn, DEFAULT_SETTLE_MS);
    }

    /** A rail that comes down in plain reverse order. */
    public RailStep(RailSpec rail, List<String> dependsOn, int settleMs) {
        this(rail, dependsOn, settleMs, false);
    }

    /** The rail's name. */
    public String name() {
        return rail.name();
    }
}
