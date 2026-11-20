package io.github.rkaushikdev.carrierworks.persistence;

import io.github.rkaushikdev.carrierworks.artifacts.Provenance;
import io.github.rkaushikdev.carrierworks.util.Times;
import jakarta.persistence.EntityManager;
import java.util.List;

/** The builds that produced each image. */
public final class ProvenanceRepository {

    private final EntityManager session;

    ProvenanceRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /** Record one build of an image. */
    public Provenance add(Provenance build) {
        ProvenanceRow row = new ProvenanceRow();
        row.setDigest(build.digest());
        row.setDesign(build.design());
        row.setSourceRevision(build.sourceRevision());
        row.setBuildId(build.buildId());
        row.setBuiltAt(Times.parse(build.builtAt()));
        row.setToolVersion(build.toolVersion());
        row.setConstraintsDigest(build.constraintsDigest());
        row.setBuilder(build.builder());
        row.setBranch(build.branch());
        row.setDirty(build.dirty());
        session.persist(row);
        return build;
    }

    /** Every build of one image, oldest first. */
    public List<Provenance> forDigest(String digest) {
        return session
                .createQuery(
                        "select p from ProvenanceRow p where p.digest = :digest order by p.builtAt, p.id",
                        ProvenanceRow.class)
                .setParameter("digest", digest)
                .getResultList()
                .stream()
                .map(row -> new Provenance(
                        row.getDigest(),
                        row.getDesign(),
                        row.getSourceRevision(),
                        row.getBuildId(),
                        Times.iso(row.getBuiltAt()),
                        row.getToolVersion(),
                        row.getConstraintsDigest(),
                        row.getBuilder(),
                        row.getBranch(),
                        row.isDirty()))
                .toList();
    }

    /** Builds of one image whose inputs differ from the first one recorded. */
    public List<String> disagreements(String digest) {
        List<Provenance> builds = forDigest(digest);
        if (builds.size() < 2) {
            return List.of();
        }
        Provenance first = builds.getFirst();
        return builds.subList(1, builds.size()).stream()
                .filter(build -> !build.sameSourceAs(first))
                .map(Provenance::buildId)
                .toList();
    }
}
