package io.github.rkaushikdev.carrierworks.artifacts;

import io.github.rkaushikdev.carrierworks.Ids;
import java.util.ArrayList;
import java.util.List;

/**
 * Which images are allowed where, and what has to be true first.
 *
 * <p>The rules are written as data because they get argued about. Twice now somebody has wanted a
 * partial image in qual for a specific regression, and both times the answer was no, and having the
 * reason as a line rather than as a condition buried in a function is what made that a two minute
 * conversation.
 *
 * <p>Nothing here promotes anything. It decides, and it says why, and the caller does the writing.
 */
public final class Promotions {

    /** How many clean loads on the bench an image needs before it can go golden. */
    public static final int DEFAULT_SOAK_LOADS = 3;

    private Promotions() {}

    /** Decide whether an image with no evidence behind it may move to a channel. */
    public static PromotionDecision evaluate(ArtifactRecord record, String toChannel) {
        return evaluate(record, toChannel, PromotionEvidence.NONE, DEFAULT_SOAK_LOADS);
    }

    /** Decide whether an image may move to a channel, wanting the usual soak for golden. */
    public static PromotionDecision evaluate(ArtifactRecord record, String toChannel, PromotionEvidence evidence) {
        return evaluate(record, toChannel, evidence, DEFAULT_SOAK_LOADS);
    }

    /**
     * Decide whether an image may move to a channel, and say why not.
     *
     * <p>Promotion is one step at a time on purpose. An image that has never run on the bench cannot
     * go straight to golden however much evidence is attached to it, because the evidence for golden
     * is meant to be evidence gathered in qual. Demotion is always allowed: somebody has found a
     * problem and the image needs to stop being used, which is not a thing to argue with.
     *
     * @param soakLoads how many clean loads golden wants
     * @throws io.github.rkaushikdev.carrierworks.ValidationError when either channel is not one of
     *     the three
     */
    public static PromotionDecision evaluate(
            ArtifactRecord record, String toChannel, PromotionEvidence evidence, int soakLoads) {
        String target = Ids.slug(toChannel);
        Channel here = Channel.parse(record.channel());
        Channel there = Channel.parse(target);
        List<String> blockers = new ArrayList<>();
        if (there == here) {
            blockers.add("the image is already in " + there);
        } else if (there.rank() < here.rank()) {
            return new PromotionDecision(record.digest(), here, there);
        } else if (there.rank() - here.rank() > 1) {
            blockers.add("promotion goes one channel at a time, " + here + " to " + there + " skips one");
        } else {
            blockers.addAll(qualBlockers(record, evidence));
            if (there == Channel.GOLDEN) {
                blockers.addAll(goldenBlockers(evidence, soakLoads));
            }
        }
        return new PromotionDecision(record.digest(), here, there, blockers);
    }

    private static List<String> qualBlockers(ArtifactRecord record, PromotionEvidence evidence) {
        List<String> blockers = new ArrayList<>();
        if (record.isPartial()) {
            blockers.add("a partial image cannot be qualified on its own");
        }
        if (evidence.timingClosed().isEmpty()) {
            blockers.add("no timing report has been read for this image");
        } else if (!evidence.timingClosed().get()) {
            blockers.add("the design did not close timing");
        }
        if (evidence.provenance().isEmpty()) {
            blockers.add("nothing recorded where this image came from");
        } else if (!evidence.reproducible()) {
            blockers.add("the build cannot be reproduced from a clean tree");
        }
        return blockers;
    }

    private static List<String> goldenBlockers(PromotionEvidence evidence, int soak) {
        List<String> blockers = new ArrayList<>();
        if (evidence.approvals().isEmpty()) {
            blockers.add("nobody has approved this image");
        }
        if (evidence.successfulLoads() < soak) {
            blockers.add("only " + evidence.successfulLoads() + " clean loads on the bench, " + soak + " are wanted");
        }
        if (evidence.failedLoads() != 0) {
            blockers.add(evidence.failedLoads() + " loads of this image have failed");
        }
        if (!evidence.disagreements().isEmpty()) {
            blockers.add("two builds of this image disagree about their source");
        }
        return blockers;
    }
}
