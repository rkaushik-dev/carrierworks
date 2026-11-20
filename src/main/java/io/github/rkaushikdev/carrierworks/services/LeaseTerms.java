package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.leases.Lease;

/**
 * How long, how urgently and how firmly somebody wants the board they are taking.
 *
 * <p>The standard terms are the site's default length, priority {@value Lease#DEFAULT_PRIORITY},
 * and preemptible. Terms are checked when a lease is taken on them, against the settings of the
 * bench taking it, so the same terms can be refused on a site with a shorter ceiling.
 *
 * @param minutes how long the board is wanted for, or zero for the site's default length
 * @param priority {@value Lease#MIN_PRIORITY} is the most urgent, {@value Lease#MAX_PRIORITY} the
 *     least
 * @param preemptible whether a more urgent request may take the board away
 */
public record LeaseTerms(int minutes, int priority, boolean preemptible) {

    /** The site's default length, at the default priority, and preemptible. */
    public static LeaseTerms standard() {
        return new LeaseTerms(0, Lease.DEFAULT_PRIORITY, true);
    }

    /** The same terms for this many minutes, zero meaning the site's default. */
    public LeaseTerms withMinutes(int value) {
        return new LeaseTerms(value, priority, preemptible);
    }

    /** The same terms at another priority. */
    public LeaseTerms withPriority(int value) {
        return new LeaseTerms(minutes, value, preemptible);
    }

    /** The same terms, saying whether the board may be taken away. */
    public LeaseTerms withPreemptible(boolean value) {
        return new LeaseTerms(minutes, priority, value);
    }
}
