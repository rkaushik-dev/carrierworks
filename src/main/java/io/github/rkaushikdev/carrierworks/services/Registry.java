package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.Conflict;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactStore;
import io.github.rkaushikdev.carrierworks.artifacts.PromotionDecision;
import io.github.rkaushikdev.carrierworks.artifacts.PromotionEvidence;
import io.github.rkaushikdev.carrierworks.artifacts.Promotions;
import io.github.rkaushikdev.carrierworks.artifacts.Provenance;
import io.github.rkaushikdev.carrierworks.bitstream.Bitstream;
import io.github.rkaushikdev.carrierworks.jobs.JobState;
import io.github.rkaushikdev.carrierworks.jobs.JobStore;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.reports.Evidence;
import io.github.rkaushikdev.carrierworks.reports.LoadCounts;
import io.github.rkaushikdev.carrierworks.reports.TimingClosure;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Taking an image in, and moving it between channels.
 *
 * <p>Ingest writes twice on purpose: the bytes go into the content addressed store on disk and a
 * row goes into the database. The store is the truth about the image and the row is the truth about
 * what the bench thinks of it, and keeping them apart means a channel change never rewrites a file.
 *
 * <p>An image that is already stored is not an error. The nightly build produces the same bytes for
 * an unchanged design every night, and the second ingest hands back the record that is already
 * there.
 *
 * <p>Promotion asks the rules rather than deciding anything, and it writes only when the rules say
 * yes. The evidence comes from the caller because it is gathered from four places and promoting
 * should not know about any of them; {@link #evidenceFrom} is the usual way to gather it.
 */
public final class Registry {

    /** The failure codes of jobs that never reached the board, which say nothing about the image. */
    private static final Set<String> NEVER_REACHED = Set.of("gate", "abandoned");

    private Registry() {}

    /** {@link #ingest(UnitOfWork, ArtifactStore, Bitstream, Instant, String)} into the dev channel. */
    public static ArtifactRecord ingest(UnitOfWork unit, ArtifactStore store, Bitstream image, Instant now) {
        return ingest(unit, store, image, now, ArtifactRecord.DEFAULT_CHANNEL);
    }

    /** Store an image and record it, or hand back the record already there. */
    public static ArtifactRecord ingest(
            UnitOfWork unit, ArtifactStore store, Bitstream image, Instant now, String channel) {
        ArtifactRecord stored = store.put(image, channel);
        try {
            return unit.artifacts().get(stored.digest());
        } catch (NotFound unrecorded) {
            return unit.artifacts().add(stored, now);
        }
    }

    /** {@link #promote(UnitOfWork, String, String, PromotionEvidence, int)} with no evidence at all. */
    public static PromotionDecision promote(UnitOfWork unit, String digest, String channel) {
        return promote(unit, digest, channel, PromotionEvidence.NONE);
    }

    /** {@link #promote(UnitOfWork, String, String, PromotionEvidence, int)} wanting the usual soak for golden. */
    public static PromotionDecision promote(
            UnitOfWork unit, String digest, String channel, PromotionEvidence evidence) {
        return promote(unit, digest, channel, evidence, Promotions.DEFAULT_SOAK_LOADS);
    }

    /**
     * Move an image to another channel if the rules allow it.
     *
     * <p>A refusal is a conflict rather than a validation failure: what was asked for is perfectly
     * sensible and the image is simply not ready. The refusal carries every blocker, so a caller can
     * show them.
     *
     * @param soakLoads how many clean loads golden wants
     * @throws Conflict when the rules say no
     * @throws io.github.rkaushikdev.carrierworks.ValidationError when the channel is not one of the
     *     three
     */
    public static PromotionDecision promote(
            UnitOfWork unit, String digest, String channel, PromotionEvidence evidence, int soakLoads) {
        ArtifactRecord record = unit.artifacts().get(digest);
        PromotionDecision decision = Promotions.evaluate(record, channel, evidence, soakLoads);
        if (!decision.allowed()) {
            throw new Conflict(
                    "that image cannot move channel yet",
                    Details.of(
                            "digest",
                            record.shortDigest(),
                            "to",
                            decision.toChannel().value(),
                            "blockers",
                            String.join("; ", decision.blockers())));
        }
        unit.artifacts().save(record.withChannel(decision.toChannel()));
        return decision;
    }

    /**
     * How many loads of an image passed, and how many failed after reaching a board.
     *
     * <p>Reads the job rows rather than an in-memory store, because the evidence for a promotion has
     * to survive a restart of whatever asked for it.
     */
    public static LoadCounts countedLoads(UnitOfWork unit, String digest) {
        int passed = 0;
        int failed = 0;
        for (ProgramJob job : unit.jobs().forDigest(digest)) {
            if (job.succeeded()) {
                passed++;
            } else if (job.state() == JobState.FAILED && !NEVER_REACHED.contains(job.errorCode())) {
                failed++;
            }
        }
        return new LoadCounts(passed, failed);
    }

    /** The same count against an in-memory job store, for the dry run mode. */
    public static LoadCounts loadCountsFromStore(JobStore jobs, String digest) {
        return Evidence.loadCounts(jobs, digest);
    }

    /**
     * Write down the build that produced an image.
     *
     * @throws NotFound when the image is not known
     */
    public static Provenance recordBuild(UnitOfWork unit, Provenance build) {
        unit.artifacts().get(build.digest());
        return unit.provenance().add(build);
    }

    /** {@link #evidenceFrom(UnitOfWork, String, Optional, Collection)} with no claim about timing and no approvals. */
    public static PromotionEvidence evidenceFrom(UnitOfWork unit, String digest) {
        return evidenceFrom(unit, digest, Optional.empty(), List.of());
    }

    /**
     * The evidence a promotion reads, out of the database and the caller.
     *
     * <p>Timing comes from the stored build report when there is one. The argument is only a
     * fallback for a site that does not upload reports at all, and it is deliberately the weaker
     * source: a caller can claim anything. Repeated approvals count once.
     *
     * @param timingClosed what the caller says about timing, used only when no report was read
     */
    public static PromotionEvidence evidenceFrom(
            UnitOfWork unit, String digest, Optional<Boolean> timingClosed, Collection<String> approvals) {
        Optional<TimingClosure> stored = Reports.timingFor(unit, digest);
        LoadCounts loads = countedLoads(unit, digest);
        return PromotionEvidence.builder()
                .timingClosed(stored.map(TimingClosure::closed).or(() -> timingClosed))
                .worstSlackPs(stored.map(TimingClosure::worstSlackPs))
                .successfulLoads(loads.passed())
                .failedLoads(loads.failed())
                .approvals(List.copyOf(new TreeSet<>(approvals)))
                .provenance(unit.provenance().forDigest(digest))
                .disagreements(unit.provenance().disagreements(digest))
                .build();
    }
}
