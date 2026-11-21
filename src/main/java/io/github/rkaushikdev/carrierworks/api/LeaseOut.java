package io.github.rkaushikdev.carrierworks.api;

import io.github.rkaushikdev.carrierworks.leases.Lease;
import java.time.Instant;
import java.util.Optional;

/**
 * A lease on the way out, live or closed.
 *
 * @param id the lease id, such as {@code lease-00001}
 * @param board the asset tag of the board held
 * @param holder who holds it
 * @param purpose what it is held for
 * @param takenAt when it was taken
 * @param expiresAt when it runs out
 * @param priority how urgent it is, one being the most
 * @param renewals how many times it has been renewed
 * @param preemptible whether something more urgent may take the board away
 * @param releasedAt when it was given back, or null while it is held
 * @param releaseReason why it was given back, or nothing while it is held
 */
public record LeaseOut(
        String id,
        String board,
        String holder,
        String purpose,
        Instant takenAt,
        Instant expiresAt,
        int priority,
        int renewals,
        boolean preemptible,
        Optional<Instant> releasedAt,
        String releaseReason) {

    /** One lease, as it stands. */
    public static LeaseOut of(Lease lease) {
        return new LeaseOut(
                lease.id(),
                lease.board(),
                lease.holder(),
                lease.purpose(),
                lease.takenAt(),
                lease.expiresAt(),
                lease.priority(),
                lease.renewals(),
                lease.preemptible(),
                lease.releasedAt(),
                lease.releaseReason());
    }
}
