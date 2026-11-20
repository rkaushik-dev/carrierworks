package io.github.rkaushikdev.carrierworks.persistence;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Leases, live and closed. */
public final class LeaseRepository {

    private final EntityManager session;

    LeaseRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** Record a new lease. */
    public Lease add(Lease lease) {
        session.persist(Mappers.leaseToRow(lease));
        return lease;
    }

    /** One lease, or a not found. */
    public Lease get(String leaseId) {
        return Mappers.leaseFromRow(row(leaseId));
    }

    /** Write what can change about a lease: its expiry, renewals and release. */
    public Lease save(Lease lease) {
        LeaseRow row = row(lease.id());
        row.setExpiresAt(lease.expiresAt());
        row.setRenewals(lease.renewals());
        row.setReleasedAt(lease.releasedAt().orElse(null));
        row.setReleaseReason(lease.releaseReason());
        return lease;
    }

    /** The live lease on a board at this moment, if there is one. */
    public Optional<Lease> forBoard(String tag, Instant now) {
        return session.createQuery(
                        "select l from LeaseRow l where l.boardTag = :board and l.releasedAt is null"
                                + " and l.expiresAt > :now order by l.takenAt desc",
                        LeaseRow.class)
                .setParameter("board", Records.boardKey(tag))
                .setParameter("now", now)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(Mappers::leaseFromRow);
    }

    /** Every live lease, oldest first. */
    public List<Lease> active(Instant now) {
        return leases(session.createQuery(
                        "select l from LeaseRow l where l.releasedAt is null and l.expiresAt > :now order by l.takenAt",
                        LeaseRow.class)
                .setParameter("now", now)
                .getResultList());
    }

    /** Every live lease one holder has, oldest first. */
    public List<Lease> heldBy(String holder, Instant now) {
        return leases(session.createQuery(
                        "select l from LeaseRow l where l.holder = :holder and l.releasedAt is null"
                                + " and l.expiresAt > :now order by l.takenAt",
                        LeaseRow.class)
                .setParameter("holder", holder.strip().toLowerCase(Locale.ROOT))
                .setParameter("now", now)
                .getResultList());
    }

    /** Every lease a board has ever had, oldest first. */
    public List<Lease> history(String tag) {
        return leases(session.createQuery(
                        "select l from LeaseRow l where l.boardTag = :board order by l.takenAt", LeaseRow.class)
                .setParameter("board", Records.boardKey(tag))
                .getResultList());
    }

    private LeaseRow row(String leaseId) {
        LeaseRow row = session.find(LeaseRow.class, leaseId);
        if (row == null) {
            throw new NotFound("lease", leaseId);
        }
        return row;
    }

    private static List<Lease> leases(List<LeaseRow> rows) {
        return rows.stream().map(Mappers::leaseFromRow).toList();
    }
}
