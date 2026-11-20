package io.github.rkaushikdev.carrierworks.persistence;

import static io.github.rkaushikdev.carrierworks.persistence.Constraints.refused;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersistenceArtifactsTest {

    private static final Instant NOW = Instant.parse("2025-11-27T06:00:00Z");
    private static final String DIGEST = "ab".repeat(32);

    private BenchDatabase database;
    private Engine engine;

    @BeforeEach
    void open() {
        database = BenchDatabase.inMemory();
        engine = database.engine();
    }

    @AfterEach
    void close() {
        database.close();
    }

    private static ArtifactRow artifact() {
        return artifact(DIGEST, "dev", 3_000_000);
    }

    private static ArtifactRow artifact(String digest, String channel, int length) {
        ArtifactRow row = new ArtifactRow();
        row.setDigest(digest);
        row.setDesign("radar_frontend");
        row.setDevice("xc7k325t");
        row.setLengthBytes(length);
        row.setKind("full");
        row.setChannel(channel);
        row.setStoredAt(NOW);
        return row;
    }

    private static ProvenanceRow build() {
        return build(DIGEST, "nightly-1841", "9f2c11a");
    }

    private static ProvenanceRow build(String digest, String buildId, String revision) {
        ProvenanceRow row = new ProvenanceRow();
        row.setDigest(digest);
        row.setDesign("radar_frontend");
        row.setSourceRevision(revision);
        row.setBuildId(buildId);
        row.setBuiltAt(NOW);
        row.setConstraintsDigest("c0ffee");
        return row;
    }

    private static BuildReportRow report() {
        return report(DIGEST, 400);
    }

    private static BuildReportRow report(String digest, int wns) {
        BuildReportRow row = new BuildReportRow();
        row.setDigest(digest);
        row.setDesign("radar_frontend");
        row.setReadAt(NOW);
        row.setWnsPs(wns);
        row.setTnsPs(0);
        row.setWhsPs(100);
        row.setThsPs(0);
        row.setSetupTotal(100_000);
        row.setHoldTotal(100_000);
        row.setLuts(45_000);
        row.setRegisters(61_000);
        return row;
    }

    @Test
    void anArtifactRoundTrips() {
        engine.sessionScope(session -> session.persist(artifact()));
        engine.sessionScope(session -> {
            ArtifactRow found = session.find(ArtifactRow.class, DIGEST);
            assertThat(found.getDesign()).isEqualTo("radar_frontend");
            assertThat(found.getChannel()).isEqualTo("dev");
            assertThat(found.getTags()).isEmpty();
        });
    }

    @Test
    void aShortDigestIsRefused() {
        refused(() -> engine.sessionScope(session -> session.persist(artifact("abcdef", "dev", 3_000_000))));
    }

    @Test
    void anEmptyImageIsRefused() {
        refused(() -> engine.sessionScope(session -> session.persist(artifact(DIGEST, "dev", 0))));
    }

    @Test
    void twoBuildsOfOneImageAreBothKept() {
        engine.sessionScope(session -> {
            ArtifactRow row = artifact();
            row.addBuild(build());
            row.addBuild(build(DIGEST, "rebuild-2", "a".repeat(40)));
            session.persist(row);
        });
        engine.sessionScope(
                session -> assertThat(session.find(ArtifactRow.class, DIGEST).getBuilds())
                        .extracting(ProvenanceRow::getBuildId)
                        .containsExactly("nightly-1841", "rebuild-2"));
    }

    @Test
    void theSameBuildCannotBeRecordedTwice() {
        engine.sessionScope(session -> {
            ArtifactRow row = artifact();
            row.addBuild(build());
            session.persist(row);
        });
        refused(() -> engine.sessionScope(session -> session.persist(build())));
    }

    @Test
    void provenanceNeedsAnImageToHangOff() {
        refused(() ->
                engine.sessionScope(session -> session.persist(build("cd".repeat(32), "nightly-1841", "9f2c11a"))));
    }

    @Test
    void aReportHangsOffItsImage() {
        engine.sessionScope(session -> {
            ArtifactRow row = artifact();
            row.addReport(report());
            session.persist(row);
        });
        engine.sessionScope(session -> {
            BuildReportRow found =
                    session.find(ArtifactRow.class, DIGEST).getReports().getFirst();
            assertThat(found.getWnsPs()).isEqualTo(400);
            assertThat(found.isBaseline()).isFalse();
        });
    }

    @Test
    void oneImageHasOneReport() {
        engine.sessionScope(session -> {
            ArtifactRow row = artifact();
            row.addReport(report());
            session.persist(row);
        });
        refused(() -> engine.sessionScope(session -> session.persist(report(DIGEST, 100))));
    }

    @Test
    void negativeFailingCountsAreRefused() {
        refused(() -> engine.sessionScope(session -> {
            ArtifactRow row = artifact();
            BuildReportRow entry = report();
            entry.setSetupFailing(-1);
            row.addReport(entry);
            session.persist(row);
        }));
    }

    @Test
    void deletingAnImageTakesItsBuildsAndReport() {
        engine.sessionScope(session -> {
            ArtifactRow row = artifact();
            row.addBuild(build());
            row.addReport(report());
            session.persist(row);
        });
        engine.sessionScope(session -> session.remove(session.find(ArtifactRow.class, DIGEST)));
        engine.sessionScope(session -> {
            assertThat(session.createQuery("select p from ProvenanceRow p", ProvenanceRow.class)
                            .getResultList())
                    .isEmpty();
            assertThat(session.createQuery("select r from BuildReportRow r", BuildReportRow.class)
                            .getResultList())
                    .isEmpty();
        });
    }

    @Test
    void imagesAreFoundByChannelAndDesign() {
        engine.sessionScope(session -> {
            session.persist(artifact());
            session.persist(artifact("cd".repeat(32), "qual", 3_000_000));
        });
        engine.sessionScope(session -> {
            assertThat(session.createQuery("select a from ArtifactRow a where a.channel = 'qual'", ArtifactRow.class)
                            .getResultList())
                    .extracting(ArtifactRow::getDigest)
                    .containsExactly("cd".repeat(32));
            assertThat(session.createQuery(
                                    "select a from ArtifactRow a where a.design = 'radar_frontend'", ArtifactRow.class)
                            .getResultList())
                    .hasSize(2);
        });
    }

    @Test
    void aBaselineReportIsFlaggedRatherThanMoved() {
        engine.sessionScope(session -> {
            ArtifactRow row = artifact();
            BuildReportRow entry = report();
            entry.setBaseline(true);
            row.addReport(entry);
            session.persist(row);
        });
        engine.sessionScope(session -> assertThat(session.createQuery(
                                "select r from BuildReportRow r where r.baseline = true", BuildReportRow.class)
                        .getResultList())
                .extracting(BuildReportRow::getDigest)
                .containsExactly(DIGEST));
    }

    @Test
    void buildsAreFoundByRevision() {
        engine.sessionScope(session -> {
            ArtifactRow row = artifact();
            row.addBuild(build());
            session.persist(row);
        });
        engine.sessionScope(session -> {
            var found = session.createQuery(
                            "select p from ProvenanceRow p where p.sourceRevision = '9f2c11a'", ProvenanceRow.class)
                    .getResultList();
            assertThat(found).hasSize(1);
            assertThat(found.getFirst().getBuiltAt()).isEqualTo(NOW);
            assertThat(found.getFirst().getBuiltAt()).isBefore(NOW.plus(Duration.ofSeconds(1)));
        });
    }
}
