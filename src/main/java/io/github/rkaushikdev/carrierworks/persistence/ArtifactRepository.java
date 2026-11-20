package io.github.rkaushikdev.carrierworks.persistence;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** The image registry's rows, which sit beside the files themselves. */
public final class ArtifactRepository {

    private final EntityManager session;

    ArtifactRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** Record a stored image. */
    public ArtifactRecord add(ArtifactRecord record, Instant storedAt) {
        session.persist(Mappers.artifactToRow(record, storedAt));
        return record;
    }

    /** One image's record, or a not found. */
    public ArtifactRecord get(String digest) {
        return Mappers.artifactFromRow(row(digest));
    }

    /** Write what can change about an image: its channel and its tags. */
    public ArtifactRecord save(ArtifactRecord record) {
        ArtifactRow row = row(record.digest());
        row.setChannel(record.channel());
        row.setTags(String.join(",", record.tags()));
        return record;
    }

    /** Every image of one design, newest first. */
    public List<ArtifactRecord> forDesign(String design) {
        return records(session.createQuery(
                        "select a from ArtifactRow a where a.design = :design order by a.storedAt desc",
                        ArtifactRow.class)
                .setParameter("design", design.strip().toLowerCase(Locale.ROOT))
                .getResultList());
    }

    /** Every image in one promotion channel, newest first. */
    public List<ArtifactRecord> inChannel(String channel) {
        return records(session.createQuery(
                        "select a from ArtifactRow a where a.channel = :channel order by a.storedAt desc",
                        ArtifactRow.class)
                .setParameter("channel", channel.strip().toLowerCase(Locale.ROOT))
                .getResultList());
    }

    private ArtifactRow row(String digest) {
        ArtifactRow row = session.find(ArtifactRow.class, digest);
        if (row == null) {
            throw new NotFound("artifact", digest);
        }
        return row;
    }

    private static List<ArtifactRecord> records(List<ArtifactRow> rows) {
        return rows.stream().map(Mappers::artifactFromRow).toList();
    }
}
