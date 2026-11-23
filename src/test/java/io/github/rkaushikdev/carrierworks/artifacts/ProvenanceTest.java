package io.github.rkaushikdev.carrierworks.artifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.bitstream.Bitstream;
import io.github.rkaushikdev.carrierworks.bitstream.BuildStamp;
import io.github.rkaushikdev.carrierworks.bitstream.Images;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProvenanceTest {

    private static final String REVISION = "9f2c11a4b7d3e5061728394a5b6c7d8e9f012345";
    private static final String DIGEST = "ab".repeat(32);

    @TempDir
    Path tmp;

    private static Bitstream image() {
        return Images.image()
                .part(Images.ARTIX_PART)
                .idcode(0x03631093L)
                .frames(8)
                .stamp(LocalDateTime.of(2025, 5, 26, 2, 11))
                .bitstream();
    }

    /** The nightly build of the radar front end, as the build system describes it. */
    private static Provenance.Builder build(String digest) {
        return new Provenance.Builder(digest, "radar_frontend", REVISION, "nightly-1841", "2025-05-26T02:11:00+00:00")
                .toolVersion("2024.1")
                .constraintsDigest("c0ffee")
                .builder("agent-04")
                .branch("main");
    }

    private ArtifactStore store() {
        return new ArtifactStore(tmp.resolve("artifacts"));
    }

    @Test
    void theImageCarriesTheBuildTimeItsRecordClaims() {
        Provenance recorded = build(DIGEST).build();
        assertThat(image().stamp().map(BuildStamp::describe)).contains("2025-05-26 02:11:00");
        assertThat(recorded.builtAt()).startsWith("2025-05-26T02:11");
    }

    @Test
    void aBuildRecordsWhereItCameFrom() {
        Provenance provenance = build(DIGEST).build();
        assertThat(provenance.shortRevision()).isEqualTo(REVISION.substring(0, 12));
        assertThat(provenance.design()).isEqualTo("radar_frontend");
        assertThat(provenance.branch()).isEqualTo("main");
        assertThat(provenance.describe())
                .isEqualTo("radar_frontend from 9f2c11a4b7d3 on main (clean) build nightly-1841");
    }

    @Test
    void aShortRevisionIsStillARevision() {
        assertThat(build(DIGEST).sourceRevision("9F2C11A").build().sourceRevision())
                .isEqualTo("9f2c11a");
    }

    @ParameterizedTest
    @CsvSource({"source_revision, not-a-sha", "source_revision, ''", "build_id, '  '", "built_at, ''"})
    void aBuildMissingItsFactsIsRefused(String field, String value) {
        Provenance.Builder builder = build(DIGEST);
        switch (field) {
            case "source_revision" -> builder.sourceRevision(value);
            case "build_id" -> builder.buildId(value);
            case "built_at" -> builder.builtAt(value);
            default -> throw new IllegalArgumentException("no field " + field);
        }
        assertThatThrownBy(builder::build).isInstanceOf(ValidationError.class);
    }

    @Test
    void aCleanBuildWithItsConstraintsCanBeRedone() {
        assertThat(build(DIGEST).build().reproducible()).isTrue();
    }

    @Test
    void aDirtyTreeIsNotReproducible() {
        assertThat(build(DIGEST).dirty(true).build().reproducible()).isFalse();
    }

    @Test
    void aBuildWithoutAConstraintsDigestIsNotReproducibleEither() {
        assertThat(build(DIGEST).constraintsDigest("").build().reproducible()).isFalse();
    }

    @Test
    void twoBuildsOffTheSameInputsAgree() {
        Provenance first = build(DIGEST).build();
        Provenance second =
                build(DIGEST).buildId("nightly-1842").builder("agent-07").build();
        assertThat(first.sameSourceAs(second)).isTrue();
    }

    @Test
    void aChangedConstraintMakesTwoBuildsDisagree() {
        Provenance first = build(DIGEST).build();
        Provenance second = build(DIGEST).constraintsDigest("decaf1").build();
        assertThat(first.sameSourceAs(second)).isFalse();
    }

    @Test
    void aBuildRoundTripsThroughJson() {
        Provenance provenance = build(DIGEST).build();
        assertThat(Provenance.fromJson(provenance.toJson())).isEqualTo(provenance);
    }

    @Test
    void aBuildIsRecordedBesideItsImage() {
        ArtifactStore store = store();
        ArtifactRecord record = store.put(image());
        ProvenanceLog log = new ProvenanceLog(store.root());
        List<Provenance> kept = log.record(build(record.digest()).build());
        assertThat(kept).hasSize(1);
        assertThat(log.forDigest(record.digest()).getFirst().buildId()).isEqualTo("nightly-1841");
    }

    @Test
    void recordingTheSameBuildTwiceChangesNothing() {
        ArtifactStore store = store();
        ArtifactRecord record = store.put(image());
        ProvenanceLog log = new ProvenanceLog(store.root());
        log.record(build(record.digest()).build());
        log.record(build(record.digest()).build());
        assertThat(log.forDigest(record.digest())).hasSize(1);
    }

    @Test
    void twoBuildsOfOneImageAreBothKept() {
        ArtifactStore store = store();
        ArtifactRecord record = store.put(image());
        ProvenanceLog log = new ProvenanceLog(store.root());
        log.record(build(record.digest()).build());
        log.record(build(record.digest())
                .buildId("nightly-1842")
                .sourceRevision("a".repeat(40))
                .build());
        assertThat(log.forDigest(record.digest())).hasSize(2);
        assertThat(log.revisions(record.digest()))
                .containsExactly("9f2c11a4b7d3e5061728394a5b6c7d8e9f012345", "a".repeat(40));
    }

    @Test
    void aBuildFromDifferentInputsIsFlaggedAsADisagreement() {
        ArtifactStore store = store();
        ArtifactRecord record = store.put(image());
        ProvenanceLog log = new ProvenanceLog(store.root());
        log.record(build(record.digest()).build());
        log.record(build(record.digest())
                .buildId("rebuild-2")
                .constraintsDigest("decaf1")
                .build());
        assertThat(log.disagreements(record.digest())).containsExactly("rebuild-2");
    }

    @Test
    void twoBuildsThatAgreeAreNotADisagreement() {
        ArtifactStore store = store();
        ArtifactRecord record = store.put(image());
        ProvenanceLog log = new ProvenanceLog(store.root());
        log.record(build(record.digest()).build());
        log.record(build(record.digest()).buildId("rebuild-2").build());
        assertThat(log.disagreements(record.digest())).isEmpty();
    }

    @Test
    void aSingleBuildCannotDisagreeWithAnything() {
        ArtifactStore store = store();
        ArtifactRecord record = store.put(image());
        ProvenanceLog log = new ProvenanceLog(store.root());
        log.record(build(record.digest()).build());
        assertThat(log.disagreements(record.digest())).isEmpty();
    }

    @Test
    void recordingAgainstAnImageNobodyStoredIsANotFound() {
        ProvenanceLog log = new ProvenanceLog(tmp.resolve("artifacts"));
        assertThatThrownBy(() -> log.record(build(DIGEST).build())).isInstanceOf(NotFound.class);
    }

    @Test
    void anImageWithNoBuildsRecordedHasNone() {
        ArtifactStore store = store();
        ArtifactRecord record = store.put(image());
        ProvenanceLog log = new ProvenanceLog(store.root());
        assertThat(log.forDigest(record.digest())).isEmpty();
        assertThat(log.revisions(record.digest())).isEmpty();
    }
}
