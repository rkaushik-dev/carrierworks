package io.github.rkaushikdev.carrierworks.reports;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.carrierworks.artifacts.ArtifactKind;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactStore;
import io.github.rkaushikdev.carrierworks.artifacts.PromotionDecision;
import io.github.rkaushikdev.carrierworks.artifacts.PromotionEvidence;
import io.github.rkaushikdev.carrierworks.artifacts.Promotions;
import io.github.rkaushikdev.carrierworks.artifacts.Provenance;
import io.github.rkaushikdev.carrierworks.artifacts.ProvenanceLog;
import io.github.rkaushikdev.carrierworks.bitstream.BitFile;
import io.github.rkaushikdev.carrierworks.bitstream.Bitstream;
import io.github.rkaushikdev.carrierworks.bitstream.Packets;
import io.github.rkaushikdev.carrierworks.jobs.JobMode;
import io.github.rkaushikdev.carrierworks.jobs.JobStore;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.util.Times;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceTest {

    private static final Instant NOW = Instant.parse("2025-11-14T09:00:00Z");
    private static final String DIGEST = "ab".repeat(32);

    @TempDir
    Path tmp;

    /** How a job in {@link #jobsWith} ends. */
    private enum Outcome {
        PASSED,
        GATE,
        FAILED
    }

    private static TimingSummary timing() {
        return timing(400, 0);
    }

    private static TimingSummary timing(long wns, int failing) {
        return new TimingSummary(wns, 0, failing, 1000, 100, 0, 0, 1000);
    }

    private static Utilization utilization() {
        return new Utilization(List.of(new SiteUsage("Slice LUTs", 1000, 203800)));
    }

    private static BuildReport report() {
        return report(DIGEST, timing());
    }

    private static BuildReport report(String digest) {
        return report(digest, timing());
    }

    private static BuildReport report(String digest, TimingSummary timing) {
        return new BuildReport(digest, "radar_frontend", NOW, timing, utilization());
    }

    /** A store holding one image, and the provenance log beside it with one build of the image. */
    private record Logged(ProvenanceLog log, ArtifactRecord record) {}

    private Logged provenanceLog() {
        return provenanceLog(false);
    }

    private Logged provenanceLog(boolean dirty) {
        byte[] words = Packets.wordsToBytes(
                Packets.SYNC_WORD,
                Packets.type1(2, 12, 1),
                0x03631093L,
                Packets.type1(2, 2, 0),
                Packets.type2(2, 4),
                1,
                2,
                3,
                4,
                Packets.type1(2, 4, 1),
                5);
        Bitstream image = Bitstream.read(BitFile.build("radar_frontend", "7a100tfgg484", "d", "t", words));
        ArtifactStore store = new ArtifactStore(tmp.resolve("artifacts"));
        ArtifactRecord record = store.put(image);
        ProvenanceLog log = new ProvenanceLog(store.root());
        log.record(new Provenance.Builder(record.digest(), "radar_frontend", "9f2c11a", "nightly-1", Times.iso(NOW))
                .constraintsDigest("c0ffee")
                .dirty(dirty)
                .build());
        return new Logged(log, record);
    }

    private static JobStore jobsWith(Outcome... outcomes) {
        return jobsWith(DIGEST, outcomes);
    }

    /** A store with one finished job of this image per outcome, a minute apart. */
    private static JobStore jobsWith(String digest, Outcome... outcomes) {
        JobStore store = new JobStore();
        for (int index = 0; index < outcomes.length; index++) {
            Instant at = NOW.plus(Duration.ofMinutes(index));
            ProgramJob running =
                    store.submit("bx-14", digest, JobMode.JTAG, "team-a", at).start(at);
            switch (outcomes[index]) {
                case PASSED -> store.update(running.passed(at.plus(Duration.ofSeconds(30))));
                case GATE -> store.update(running.failed(NOW, "refused by the gates", "gate"));
                case FAILED -> store.update(running.failed(NOW, "the device did not come up", "programming"));
            }
        }
        return store;
    }

    /** The same image as a qualified full Artix image, ready to ask about golden. */
    private static ArtifactRecord qualified(ArtifactRecord record) {
        return new ArtifactRecord(record.digest(), "radar_frontend", "xc7a100t", record.length(), ArtifactKind.FULL)
                .withChannel("qual");
    }

    @Test
    void anImageWithNoReportHasNoTimingAnswer() {
        assertThat(Evidence.timingFor(new BaselineBook(), DIGEST)).isEmpty();
    }

    @Test
    void aClosedBuildReportsItsSlack() {
        BaselineBook book = new BaselineBook();
        book.record(report());
        assertThat(Evidence.timingFor(book, DIGEST)).contains(new TimingClosure(true, 100));
    }

    @Test
    void aFailedBuildSaysSo() {
        BaselineBook book = new BaselineBook();
        book.record(report(DIGEST, timing(-100, 3)));
        TimingClosure timing = Evidence.timingFor(book, DIGEST).orElseThrow();
        assertThat(timing.closed()).isFalse();
        assertThat(timing.worstSlackPs()).isEqualTo(-100);
    }

    @Test
    void loadsAreCountedFromTheJobStore() {
        assertThat(Evidence.loadCounts(jobsWith(Outcome.PASSED, Outcome.PASSED, Outcome.FAILED), DIGEST))
                .isEqualTo(new LoadCounts(2, 1));
    }

    @Test
    void aJobForAnotherImageIsNotCounted() {
        JobStore store = jobsWith(Outcome.PASSED);
        ProgramJob other = store.submit("bx-15", "cd".repeat(32), JobMode.JTAG, "team-a", NOW);
        store.update(other.start(NOW).passed(NOW));
        assertThat(Evidence.loadCounts(store, DIGEST)).isEqualTo(new LoadCounts(1, 0));
    }

    @Test
    void aJobRefusedByTheGatesIsNotAFailureOfTheImage() {
        assertThat(Evidence.loadCounts(jobsWith(Outcome.PASSED, Outcome.GATE), DIGEST))
                .isEqualTo(new LoadCounts(1, 0));
    }

    @Test
    void anUnfinishedJobCountsNeitherWay() {
        JobStore store = new JobStore();
        ProgramJob job = store.submit("bx-14", DIGEST, JobMode.JTAG, "team-a", NOW);
        store.update(job.start(NOW));
        assertThat(Evidence.loadCounts(store, DIGEST)).isEqualTo(new LoadCounts(0, 0));
    }

    @Test
    void theEvidencePullsAllFourSourcesTogether() {
        Logged logged = provenanceLog();
        BaselineBook book = new BaselineBook();
        book.record(report(logged.record().digest()));
        PromotionEvidence evidence = Evidence.evidenceFor(
                logged.record().digest(),
                book,
                jobsWith(logged.record().digest(), Outcome.PASSED, Outcome.PASSED, Outcome.PASSED),
                logged.log(),
                List.of("kmoyer", "kmoyer"));
        assertThat(evidence.timingClosed()).contains(true);
        assertThat(evidence.successfulLoads()).isEqualTo(3);
        assertThat(evidence.approvals()).containsExactly("kmoyer");
        assertThat(evidence.reproducible()).isTrue();
    }

    @Test
    void theEvidenceIsWhatPromotionReads() {
        Logged logged = provenanceLog();
        BaselineBook book = new BaselineBook();
        book.record(report(logged.record().digest()));
        PromotionEvidence evidence = Evidence.evidenceFor(
                logged.record().digest(),
                book,
                jobsWith(logged.record().digest(), Outcome.PASSED, Outcome.PASSED, Outcome.PASSED),
                logged.log(),
                List.of("kmoyer"));
        assertThat(Promotions.evaluate(qualified(logged.record()), "golden", evidence)
                        .allowed())
                .isTrue();
    }

    @Test
    void aDirtyBuildStopsTheSamePromotion() {
        Logged logged = provenanceLog(true);
        BaselineBook book = new BaselineBook();
        book.record(report(logged.record().digest()));
        PromotionEvidence evidence = Evidence.evidenceFor(
                logged.record().digest(),
                book,
                jobsWith(logged.record().digest(), Outcome.PASSED, Outcome.PASSED, Outcome.PASSED),
                logged.log(),
                List.of("kmoyer"));
        PromotionDecision decision = Promotions.evaluate(qualified(logged.record()), "golden", evidence);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.blockers().getFirst()).contains("cannot be reproduced");
    }

    @Test
    void evidenceWithoutAProvenanceLogStillBuilds() {
        PromotionEvidence evidence = Evidence.evidenceFor(DIGEST, new BaselineBook(), new JobStore());
        assertThat(evidence.provenance()).isEmpty();
        assertThat(evidence.timingClosed()).isEmpty();
        assertThat(evidence.loadAttempts()).isZero();
    }
}
