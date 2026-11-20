package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.leases.Lease;
import java.time.Instant;
import java.util.Optional;

/** Who holds what, and how to give it back. */
public interface LeaseRegistry {

    /** The live lease on a board at this moment, if there is one. */
    Optional<Lease> forBoard(String tag, Instant now);

    /** Give a lease back, saying why. */
    Lease release(String leaseId, Instant now, String reason);

    /** Give a lease back. */
    default Lease release(String leaseId, Instant now) {
        return release(leaseId, now, Lease.RELEASED);
    }
}
