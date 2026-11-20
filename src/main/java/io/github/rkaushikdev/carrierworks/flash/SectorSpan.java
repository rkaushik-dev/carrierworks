package io.github.rkaushikdev.carrierworks.flash;

/**
 * The sectors a region occupies, which is what an erase of it has to cover.
 *
 * @param first the first sector, counted from zero
 * @param last the last sector, inclusive
 */
public record SectorSpan(long first, long last) {

    /** How many sectors that is. */
    public long count() {
        return last - first + 1;
    }
}
