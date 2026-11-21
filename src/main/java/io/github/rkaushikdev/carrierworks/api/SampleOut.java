package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.thermal.ThermalSample;
import java.time.Instant;

/**
 * A stored reading on the way out.
 *
 * @param board the board it was read off
 * @param at when it was read
 * @param millidegrees the temperature, in thousandths of a degree Celsius
 * @param celsius the same temperature in degrees
 * @param source which sensor it came off
 */
public record SampleOut(String board, Instant at, int millidegrees, double celsius, String source) {

    /** One stored reading. */
    public static SampleOut of(ThermalSample sample) {
        return new SampleOut(
                sample.board(), sample.at(), sample.millidegrees(), sample.millidegrees() / 1000.0, sample.source());
    }
}
