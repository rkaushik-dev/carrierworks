package io.github.rkaushikdev.carrierworks.flash;

/**
 * How much of one sector's life has been used.
 *
 * @param sector the sector, counted from zero
 * @param erases how many times it has been erased
 * @param rated how many erases the part is rated for
 */
public record SectorWear(long sector, long erases, long rated) {

    // Past this fraction of the rated cycles a sector is worth watching.
    private static final double WARN_FRACTION = 0.8;

    /** The share of the rated erases used up; none when the part has no rating. */
    public double fraction() {
        return rated != 0 ? (double) erases / rated : 0.0;
    }

    /** Erases left before the rating, never below zero. */
    public long remaining() {
        return Math.max(0, rated - erases);
    }

    /** Whether the sector has used four fifths of its rating or more. */
    public boolean worn() {
        return fraction() >= WARN_FRACTION;
    }

    /** {@code sector 0: 4 of 100000 erases}. */
    public String describe() {
        return "sector " + sector + ": " + erases + " of " + rated + " erases";
    }
}
