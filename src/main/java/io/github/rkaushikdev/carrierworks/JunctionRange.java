package io.github.rkaushikdev.carrierworks;

/**
 * The junction temperature a part is specified for, in whole degrees celsius.
 *
 * @param low the coldest the datasheet allows
 * @param high the hottest the datasheet allows
 */
public record JunctionRange(int low, int high) {

    @Override
    public String toString() {
        return "(" + low + ", " + high + ")";
    }
}
