package io.github.rkaushikdev.carrierworks.thermal;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.Temperature;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.time.Duration;
import java.time.Instant;

/**
 * One reading off one device.
 *
 * <p>The board is kept as its normalised asset tag and the source as a slug, so {@code BX-0014} and
 * {@code bx-14} are one board. A reading outside -60 to 200 degrees is not one any device reports
 * and is refused.
 *
 * @param board the board's asset tag
 * @param at when the reading was taken
 * @param millidegrees the die temperature in millidegrees celsius
 * @param source what took the reading, as a slug
 */
public record ThermalSample(String board, Instant at, int millidegrees, String source) {

    /** Where a reading comes from unless something else says so: the device's own monitor. */
    public static final String DEFAULT_SOURCE = "sysmon";

    private static final int COLDEST_MC = -60_000;
    private static final int HOTTEST_MC = 200_000;

    public ThermalSample {
        board = Records.boardKey(board);
        source = Ids.slug(source);
        if (millidegrees < COLDEST_MC || millidegrees > HOTTEST_MC) {
            throw new ValidationError(
                    "that is not a temperature a device reports", "millidegrees", Details.of("given", millidegrees));
        }
    }

    /** A reading off the device's own system monitor. */
    public ThermalSample(String board, Instant at, int millidegrees) {
        this(board, at, millidegrees, DEFAULT_SOURCE);
    }

    /** How old the reading is; a reading from the future is no age at all. */
    public Duration age(Instant now) {
        Duration age = Duration.between(at, now);
        return age.isNegative() ? Duration.ZERO : age;
    }

    /** {@code bx-14 62.5C at 2025-09-10T12:00:00+00:00}. */
    public String describe() {
        return board + " " + Temperature.render(millidegrees) + " at " + Times.iso(at);
    }
}
