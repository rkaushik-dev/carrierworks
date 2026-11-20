package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.util.Numbers;

/**
 * One supply rail on a board, with the window it has to hold.
 *
 * <p>Keeping everything integer means a tolerance window has exact edges, so a reading that sits
 * precisely on the limit lands the same way every time instead of depending on which way the last
 * floating point operation rounded.
 *
 * @param name the rail, as a slug
 * @param nominalMv what it should read
 * @param tolerancePercent how far either way it may stray, one to twenty five percent
 * @param maxCurrentMa the most it may carry
 * @param sequence where it comes in the power up order
 * @param adjustable whether a card can ask for a different voltage on it
 */
public record RailSpec(
        String name, int nominalMv, int tolerancePercent, int maxCurrentMa, int sequence, boolean adjustable) {

    public RailSpec {
        name = Ids.slug(name);
        if (nominalMv <= 0) {
            throw new ValidationError("a rail has a positive nominal", "nominal_mv");
        }
        if (tolerancePercent < 1 || tolerancePercent > 25) {
            throw new ValidationError("tolerance is out of range", "tolerance_percent");
        }
        if (maxCurrentMa <= 0) {
            throw new ValidationError("a rail carries some current", "max_current_ma");
        }
    }

    /** A fixed rail at the head of the sequence. */
    public RailSpec(String name, int nominalMv, int tolerancePercent, int maxCurrentMa) {
        this(name, nominalMv, tolerancePercent, maxCurrentMa, 0, false);
    }

    /** The bottom of the window, rounded inward so a rail on the edge meets the tighter number. */
    public int lowLimitMv() {
        return Math.ceilDiv(nominalMv * (100 - tolerancePercent), 100);
    }

    /** The top of the window, rounded inward. */
    public int highLimitMv() {
        return Math.floorDiv(nominalMv * (100 + tolerancePercent), 100);
    }

    /** The most power the rail can deliver inside its window. */
    public int maxPowerMw() {
        return Electrical.milliwatts(highLimitMv(), maxCurrentMa);
    }

    /** Whether a reading sits inside the window, edges included. */
    public boolean contains(int millivolts) {
        return lowLimitMv() <= millivolts && millivolts <= highLimitMv();
    }

    /**
     * How far off nominal a reading is, in parts per thousand.
     *
     * <p>Signed, so a sagging rail and an overshooting one do not look alike.
     */
    public int deviationPermille(int millivolts) {
        return Math.toIntExact(Numbers.round((millivolts - nominalMv) * 1000.0 / nominalMv));
    }

    /** What this rail measured at one moment. */
    public RailReading reading(int millivolts, int milliamps) {
        return new RailReading(name, millivolts, milliamps, this);
    }

    /** A voltage reading with no current measured. */
    public RailReading reading(int millivolts) {
        return reading(millivolts, 0);
    }
}
