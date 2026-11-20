package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.util.Numbers;
import io.github.rkaushikdev.carrierworks.util.Repr;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A clock rate in whole hertz.
 *
 * <p>Whole hertz, because an adapter that claims 30 MHz and runs at 30.000001 MHz is a rounding
 * artefact and not a faster cable.
 *
 * @param hertz the rate, always positive
 */
public record Frequency(long hertz) implements Comparable<Frequency> {

    private static final Map<String, Long> UNITS = Map.of(
            "", 1L,
            "hz", 1L,
            "khz", 1_000L,
            "mhz", 1_000_000L,
            "ghz", 1_000_000_000L);

    private static final Pattern FREQUENCY = Pattern.compile("(?<value>\\d+(?:\\.\\d+)?)\\s*(?<unit>[a-z]*)");

    public Frequency {
        if (hertz <= 0) {
            throw new ValidationError("frequency must be positive", "hertz", Details.of("given", hertz));
        }
    }

    /** A rate given in kilohertz, rounded to the nearest hertz. */
    public static Frequency kilohertz(double value) {
        return new Frequency(Numbers.round(value * 1_000));
    }

    /** A rate given in megahertz, rounded to the nearest hertz. */
    public static Frequency megahertz(double value) {
        return new Frequency(Numbers.round(value * 1_000_000));
    }

    /** Read a rate however it was written: {@code 30 MHz}, {@code 6.25MHz}, {@code 400}. */
    public static Frequency parse(String text) {
        String cleaned = text.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher match = FREQUENCY.matcher(cleaned);
        if (!match.matches()) {
            throw new ValidationError("cannot read a frequency from " + Repr.of(text), "frequency");
        }
        String unit = match.group("unit");
        Long scale = UNITS.get(unit);
        if (scale == null) {
            throw new ValidationError("unknown frequency unit " + Repr.of(unit), "frequency");
        }
        return new Frequency(Numbers.round(Double.parseDouble(match.group("value")) * scale));
    }

    /** The rate in megahertz, for display. */
    public double megahertzValue() {
        return hertz / 1_000_000.0;
    }

    /** Clock period in picoseconds, rounded to the nearest whole one. */
    public long periodPs() {
        return Numbers.round(1_000_000_000_000.0 / hertz);
    }

    /** A divided clock, the way an adapter divides its own reference. */
    public Frequency dividedBy(long divisor) {
        if (divisor <= 0) {
            throw new ValidationError("divisor must be positive", "divisor", Details.of("given", divisor));
        }
        return new Frequency(hertz / divisor);
    }

    /** This clock, held down to whatever the slowest thing on the chain allows. */
    public Frequency atMost(Frequency ceiling) {
        return hertz <= ceiling.hertz ? this : ceiling;
    }

    @Override
    public int compareTo(Frequency other) {
        return Long.compare(hertz, other.hertz);
    }

    @Override
    public String toString() {
        if (hertz >= 1_000_000 && hertz % 1_000_000 == 0) {
            return hertz / 1_000_000 + " MHz";
        }
        if (hertz >= 1_000_000) {
            return Numbers.fixed(hertz / 1_000_000.0, 3) + " MHz";
        }
        if (hertz >= 1_000 && hertz % 1_000 == 0) {
            return hertz / 1_000 + " kHz";
        }
        if (hertz >= 1_000) {
            return Numbers.fixed(hertz / 1_000.0, 3) + " kHz";
        }
        return hertz + " Hz";
    }
}
