package io.github.rkaushikdev.carrierworks.reports;

import io.github.rkaushikdev.carrierworks.util.Numbers;

/**
 * One clock the design was constrained with, as the timing report's clock table lists it.
 *
 * @param name the clock's name in the constraints
 * @param periodPs its period in picoseconds
 * @param frequencyMhz its frequency in megahertz, as the report printed it
 */
public record ClockConstraint(String name, long periodPs, double frequencyMhz) {

    /** {@code adc_clk 250.000 MHz}. */
    public String describe() {
        return name + " " + Numbers.fixed(frequencyMhz, 3) + " MHz";
    }
}
