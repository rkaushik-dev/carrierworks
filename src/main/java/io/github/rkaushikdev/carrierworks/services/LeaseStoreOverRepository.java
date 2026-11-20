package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.LeaseRegistry;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The lease repository wearing the shape of the in-memory lease store.
 *
 * <p>The counterpart of {@link BookOverRepository} for leases. Releasing goes through {@link
 * Leasing#release}, so the board state moves with the lease and the trail gets its line.
 */
public final class LeaseStoreOverRepository implements LeaseRegistry {

    private final UnitOfWork unit;

    /** A lease registry reading and writing through this unit of work. */
    public LeaseStoreOverRepository(UnitOfWork unit) {
        this.unit = Objects.requireNonNull(unit, "unit");
    }

    @Override
    public Optional<Lease> forBoard(String tag, Instant now) {
        return unit.leases().forBoard(tag, now);
    }

    /** One lease, or a not found. */
    public Lease lease(String leaseId) {
        return unit.leases().get(leaseId);
    }

    /** Every live lease, oldest first. */
    public List<Lease> active(Instant now) {
        return unit.leases().active(now);
    }

    @Override
    public Lease release(String leaseId, Instant now, String reason) {
        return Leasing.release(unit, leaseId, now, reason);
    }
}
