package io.github.rkaushikdev.carrierworks.reports;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.util.Numbers;

/**
 * One row of a utilization table: how many of one kind of site a design used, out of how many.
 *
 * @param name the row's name as the tool printed it
 * @param used how many sites the design used
 * @param available how many the part has
 */
public record SiteUsage(String name, int used, int available) {

    public SiteUsage {
        if (used < 0 || available < 0) {
            throw new ValidationError("counts are not negative", "usage", Details.of("name", name));
        }
        if (used > available) {
            throw new ValidationError(
                    "a design cannot use more sites than the part has",
                    "usage",
                    Details.of("name", name, "used", used, "available", available));
        }
    }

    /**
     * How full the row is, to two places, worked out from the counts rather than read off the
     * report; a part with none of these sites is not full at all.
     */
    public double percent() {
        if (available == 0) {
            return 0.0;
        }
        return Numbers.round((double) (used * 100L) / available, 2);
    }

    /** {@code DSPs 210/840 (25.0%)}. */
    public String describe() {
        return name + " " + used + "/" + available + " (" + Numbers.fixed(percent(), 1) + "%)";
    }
}
