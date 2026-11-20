package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.util.Numbers;
import java.util.Optional;

/**
 * What a rail measured at one moment.
 *
 * @param rail the rail's name
 * @param millivolts the voltage read
 * @param milliamps the current read
 * @param spec the rail it was read off
 */
public record RailReading(String rail, int millivolts, int milliamps, RailSpec spec) {

    /** The one word for what is wrong with a rail. */
    public enum Fault {
        OVER_CURRENT("over_current"),
        UNDER_VOLTAGE("under_voltage"),
        OVER_VOLTAGE("over_voltage");

        private final String value;

        Fault(String value) {
            this.value = value;
        }

        /** The name the fault is reported under. */
        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /** Whether the voltage is inside the rail's window. */
    public boolean inWindow() {
        return spec.contains(millivolts);
    }

    /** Whether the rail is carrying more than it is rated for. */
    public boolean overCurrent() {
        return milliamps > spec.maxCurrentMa();
    }

    /** The power this reading represents. */
    public int powerMw() {
        return Electrical.milliwatts(millivolts, milliamps);
    }

    /** Whether the voltage has fallen below the window. */
    public boolean sagging() {
        return millivolts < spec.lowLimitMv();
    }

    /** What is wrong, worst first, or nothing when the rail is fine. */
    public Optional<Fault> fault() {
        if (overCurrent()) {
            return Optional.of(Fault.OVER_CURRENT);
        }
        if (sagging()) {
            return Optional.of(Fault.UNDER_VOLTAGE);
        }
        if (!inWindow()) {
            return Optional.of(Fault.OVER_VOLTAGE);
        }
        return Optional.empty();
    }

    /** {@code vccint 0.998V 3200mA}: the way people read a rail out. */
    @Override
    public String toString() {
        String volts = Numbers.fixed(millivolts / 1000.0, 3);
        volts = volts.replaceAll("0+$", "").replaceAll("\\.$", "");
        return rail + " " + volts + "V " + milliamps + "mA";
    }
}
