package io.github.rkaushikdev.carrierworks.artifacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChannelsTest {

    private static final String DIGEST = "ab".repeat(32);

    private static ArtifactRecord record() {
        return record("dev", ArtifactKind.FULL);
    }

    private static ArtifactRecord record(String channel) {
        return record(channel, ArtifactKind.FULL);
    }

    private static ArtifactRecord record(String channel, ArtifactKind kind) {
        return new ArtifactRecord(DIGEST, "radar_frontend", "xc7a100t", 3_000_000, kind).withChannel(channel);
    }

    /** The nightly build, off a clean tree with its constraints recorded. */
    private static Provenance.Builder provenance() {
        return new Provenance.Builder(DIGEST, "radar_frontend", "9f2c11a", "nightly-1841", "2025-06-01T02:00:00+00:00")
                .constraintsDigest("c0ffee");
    }

    /** Everything a promotion to qual or golden wants to see. */
    private static PromotionEvidence.Builder goodEvidence() {
        return PromotionEvidence.builder()
                .timingClosed(true)
                .worstSlackPs(147)
                .successfulLoads(4)
                .approvals("kmoyer")
                .provenance(provenance().build());
    }

    @Test
    void theChannelsAreOrderedByTrust() {
        assertThat(Arrays.stream(Channel.values()).map(Channel::value)).containsExactly("dev", "qual", "golden");
        assertThat(Channel.parse("dev").rank()).isLessThan(Channel.parse("qual").rank());
        assertThat(Channel.parse("qual").rank())
                .isLessThan(Channel.parse("golden").rank());
    }

    @Test
    void aChannelNobodyHasIsRefused() {
        assertThatThrownBy(() -> Channel.parse("staging")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aGoodImageGetsIntoQual() {
        PromotionDecision decision =
                Promotions.evaluate(record(), "qual", goodEvidence().build());
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.blockers()).isEmpty();
        assertThat(decision.describe()).isEqualTo("abababababab may go from dev to qual");
    }

    @Test
    void anImageWithNoTimingReportDoesNot() {
        PromotionEvidence evidence =
                goodEvidence().timingClosed(Optional.empty()).build();
        PromotionDecision decision = Promotions.evaluate(record(), "qual", evidence);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.blockers()).contains("no timing report has been read for this image");
    }

    @Test
    void aDesignThatMissedTimingDoesNot() {
        PromotionEvidence evidence = goodEvidence().timingClosed(false).build();
        PromotionDecision decision = Promotions.evaluate(record(), "qual", evidence);
        assertThat(decision.blockers()).contains("the design did not close timing");
    }

    @Test
    void aPartialImageCannotBeQualified() {
        PromotionDecision decision = Promotions.evaluate(
                record("dev", ArtifactKind.PARTIAL), "qual", goodEvidence().build());
        assertThat(decision.blockers()).contains("a partial image cannot be qualified on its own");
    }

    @Test
    void anImageWithNoProvenanceCannotBeQualified() {
        PromotionEvidence evidence = goodEvidence().provenance(List.of()).build();
        PromotionDecision decision = Promotions.evaluate(record(), "qual", evidence);
        assertThat(decision.blockers()).contains("nothing recorded where this image came from");
    }

    @Test
    void aDirtyBuildCannotBeQualified() {
        PromotionEvidence evidence =
                goodEvidence().provenance(provenance().dirty(true).build()).build();
        PromotionDecision decision = Promotions.evaluate(record(), "qual", evidence);
        assertThat(decision.blockers()).contains("the build cannot be reproduced from a clean tree");
    }

    @Test
    void oneDirtyBuildAmongSeveralIsStillABlocker() {
        PromotionEvidence evidence = goodEvidence()
                .provenance(
                        provenance().build(),
                        provenance().buildId("local-1").dirty(true).build())
                .build();
        PromotionDecision decision = Promotions.evaluate(record(), "qual", evidence);
        assertThat(decision.allowed()).isFalse();
    }

    @Test
    void everyBlockerIsListedRatherThanTheFirst() {
        PromotionDecision decision =
                Promotions.evaluate(record("dev", ArtifactKind.PARTIAL), "qual", PromotionEvidence.NONE);
        assertThat(decision.blockers()).hasSize(3);
    }

    @Test
    void aQualifiedImageWithApprovalAndASoakGoesGolden() {
        PromotionDecision decision =
                Promotions.evaluate(record("qual"), "golden", goodEvidence().build());
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void goldenNeedsSomebodyToSaySo() {
        PromotionEvidence evidence = goodEvidence().approvals(List.of()).build();
        PromotionDecision decision = Promotions.evaluate(record("qual"), "golden", evidence);
        assertThat(decision.blockers()).contains("nobody has approved this image");
    }

    @Test
    void goldenNeedsTheImageToHaveRunAFewTimes() {
        PromotionEvidence evidence = goodEvidence().successfulLoads(1).build();
        PromotionDecision decision = Promotions.evaluate(record("qual"), "golden", evidence);
        assertThat(decision.blockers()).contains("only 1 clean loads on the bench, 3 are wanted");
    }

    @Test
    void theSoakCountIsSettable() {
        PromotionEvidence evidence = goodEvidence().successfulLoads(1).build();
        PromotionDecision decision = Promotions.evaluate(record("qual"), "golden", evidence, 1);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void aFailedLoadBlocksGoldenHoweverManyWorked() {
        PromotionEvidence evidence =
                goodEvidence().successfulLoads(40).failedLoads(1).build();
        PromotionDecision decision = Promotions.evaluate(record("qual"), "golden", evidence);
        assertThat(decision.blockers()).contains("1 loads of this image have failed");
    }

    @Test
    void buildsThatDisagreeBlockGolden() {
        PromotionEvidence evidence = goodEvidence().disagreements("rebuild-2").build();
        PromotionDecision decision = Promotions.evaluate(record("qual"), "golden", evidence);
        assertThat(decision.blockers()).contains("two builds of this image disagree about their source");
    }

    @Test
    void goldenStillHasToMeetTheQualConditions() {
        PromotionEvidence evidence = goodEvidence().timingClosed(false).build();
        PromotionDecision decision = Promotions.evaluate(record("qual"), "golden", evidence);
        assertThat(decision.blockers()).contains("the design did not close timing");
    }

    @Test
    void promotionGoesOneChannelAtATime() {
        PromotionDecision decision =
                Promotions.evaluate(record(), "golden", goodEvidence().build());
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.blockers())
                .containsExactly("promotion goes one channel at a time, dev to golden skips one");
    }

    @Test
    void promotingToWhereItAlreadyIsSaysSo() {
        PromotionDecision decision =
                Promotions.evaluate(record("qual"), "qual", goodEvidence().build());
        assertThat(decision.blockers()).containsExactly("the image is already in qual");
    }

    @Test
    void demotionIsAlwaysAllowed() {
        PromotionDecision decision = Promotions.evaluate(record("golden"), "dev", PromotionEvidence.NONE);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.toChannel()).isEqualTo(Channel.DEV);
    }

    @Test
    void evidenceCountsItsAttempts() {
        PromotionEvidence evidence =
                goodEvidence().successfulLoads(4).failedLoads(2).build();
        assertThat(evidence.loadAttempts()).isEqualTo(6);
        assertThat(evidence.reproducible()).isTrue();
    }

    @Test
    void evidenceWithNoBuildsIsNotReproducible() {
        assertThat(PromotionEvidence.NONE.reproducible()).isFalse();
    }

    @Test
    void aDecisionWithBlockersDescribesThem() {
        PromotionDecision decision = Promotions.evaluate(record(), "qual", PromotionEvidence.NONE);
        assertThat(decision.describe()).startsWith("abababababab cannot go from dev to qual: ");
    }
}
