package io.github.rkaushikdev.carrierworks.reports;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;

/**
 * The third group of the timing summary: the pulse width checks.
 *
 * <p>These are not paths. They are the minimum high time, minimum low time and maximum period that
 * the clock pins of the clocking resources, block RAM and transceivers will tolerate, checked
 * against the clocks the constraints declare. A design can have every setup and hold path met and
 * still drive a block RAM faster than it can cycle, and when that happens it does not miss timing
 * by a little, it reads back garbage at any temperature.
 *
 * @param wpwsPs worst pulse width slack
 * @param tpwsPs total pulse width slack, the sum of the failing ones
 * @param failing pins that fail
 * @param total pins checked
 */
public record PulseWidth(long wpwsPs, long tpwsPs, int failing, int total) {

    /** What a report that prints no pulse width group is taken to say: nothing failed. */
    public static final PulseWidth NOT_REPORTED = new PulseWidth(0, 0, 0, 0);

    public PulseWidth {
        if (failing < 0 || total < 0) {
            throw new ValidationError("endpoint counts are not negative", "pulse_width");
        }
        if (failing > total) {
            throw new ValidationError(
                    "more pulse width checks failed than exist",
                    "pulse_width",
                    Details.of("given", failing + "/" + total));
        }
    }

    /** Whether every pulse width check passed. */
    public boolean met() {
        return wpwsPs >= 0 && failing == 0;
    }
}
