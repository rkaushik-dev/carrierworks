package io.github.rkaushikdev.carrierworks.persistence;

import io.github.rkaushikdev.carrierworks.AuditEntry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The append only trail. */
public final class AuditRepository {

    /** How many entries a listing shows unless it is told otherwise. */
    public static final int DEFAULT_LIMIT = 50;

    private final EntityManager session;

    AuditRepository(EntityManager session) {
        this.session = session;
    }

    /**
     * What to narrow the trail to. Every filter is optional and they combine.
     *
     * @param subject only entries about this, if given
     * @param actor only entries by this, if given
     * @param action only entries of this kind, if given
     * @param since only entries at or after this moment, if given
     * @param limit at most this many entries
     */
    public record Search(
            Optional<String> subject,
            Optional<String> actor,
            Optional<String> action,
            Optional<Instant> since,
            int limit) {

        /** The whole trail, newest first, the usual fifty at most. */
        public static Search everything() {
            return new Search(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), DEFAULT_LIMIT);
        }

        public Search subject(String value) {
            return new Search(Optional.of(value), actor, action, since, limit);
        }

        public Search actor(String value) {
            return new Search(subject, Optional.of(value), action, since, limit);
        }

        public Search action(String value) {
            return new Search(subject, actor, Optional.of(value), since, limit);
        }

        public Search since(Instant value) {
            return new Search(subject, actor, action, Optional.of(value), limit);
        }

        public Search limit(int value) {
            return new Search(subject, actor, action, since, value);
        }
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** Append one entry. */
    public AuditEntry add(AuditEntry entry) {
        AuditRow row = new AuditRow();
        row.setAt(entry.at());
        row.setActor(entry.actor());
        row.setAction(entry.action());
        row.setSubject(entry.subject());
        row.setDetail(entry.detail());
        row.setKeyId(entry.keyId());
        session.persist(row);
        return entry;
    }

    /** The newest fifty entries, newest first. */
    public List<AuditEntry> recent() {
        return recent(DEFAULT_LIMIT);
    }

    /** The newest entries, newest first. */
    public List<AuditEntry> recent(int limit) {
        return search(Search.everything().limit(limit));
    }

    /** The newest fifty entries about one thing, newest first. */
    public List<AuditEntry> forSubject(String subject) {
        return forSubject(subject, DEFAULT_LIMIT);
    }

    /** The newest entries about one thing, newest first. */
    public List<AuditEntry> forSubject(String subject, int limit) {
        return search(Search.everything().subject(subject).limit(limit));
    }

    /**
     * The trail narrowed by whatever was given, newest first.
     *
     * <p>Every filter is optional and they combine, because the two questions this table gets
     * asked are "what happened to this board" and "what has this script been doing", and the second
     * one is nearly always asked with a date on it.
     */
    public List<AuditEntry> search(Search search) {
        CriteriaBuilder criteria = session.getCriteriaBuilder();
        CriteriaQuery<AuditRow> query = criteria.createQuery(AuditRow.class);
        Root<AuditRow> row = query.from(AuditRow.class);
        List<Predicate> where = new ArrayList<>();
        search.subject().ifPresent(value -> where.add(criteria.equal(row.get("subject"), lowered(value))));
        search.actor().ifPresent(value -> where.add(criteria.equal(row.get("actor"), lowered(value))));
        search.action().ifPresent(value -> where.add(criteria.equal(row.get("action"), lowered(value))));
        search.since().ifPresent(value -> where.add(criteria.greaterThanOrEqualTo(row.<Instant>get("at"), value)));
        query.select(row)
                .where(where.toArray(Predicate[]::new))
                .orderBy(criteria.desc(row.get("at")), criteria.desc(row.get("id")));
        return session.createQuery(query).setMaxResults(search.limit()).getResultList().stream()
                .map(AuditRepository::entry)
                .toList();
    }

    /** Every entry at or after a moment, oldest first. */
    public List<AuditEntry> since(Instant when) {
        return session
                .createQuery("select a from AuditRow a where a.at >= :when order by a.at, a.id", AuditRow.class)
                .setParameter("when", when)
                .getResultList()
                .stream()
                .map(AuditRepository::entry)
                .toList();
    }

    private static String lowered(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static AuditEntry entry(AuditRow row) {
        return new AuditEntry(
                row.getAt(), row.getActor(), row.getAction(), row.getSubject(), row.getDetail(), row.getKeyId());
    }
}
