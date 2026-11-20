package io.github.rkaushikdev.carrierworks.reports;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.artifacts.PromotionEvidence;
import io.github.rkaushikdev.carrierworks.artifacts.Provenance;
import io.github.rkaushikdev.carrierworks.artifacts.ProvenanceLog;
import io.github.rkaushikdev.carrierworks.jobs.JobState;
import io.github.rkaushikdev.carrierworks.jobs.JobStore;
import io.github.rkaushikdev.carrierworks.jobs.ProgramJob;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Gathering everything known about an image into the shape promotion reads.
 *
 * <p>The promotion rules are written against a single {@link PromotionEvidence} on purpose: they are
 * the part people argue about, and an argument is much shorter when the inputs are one thing anyone
 * can print. This is what fills that object in, and it is the only place that knows where each fact
 * lives.
 *
 * <p>Timing comes from the build reports, load history comes from the job store, provenance comes
 * from its own log, and approvals come from whoever typed them. Four sources, one shape, and no rule
 * anywhere else has to know that a load count means counting passed jobs whose digest matches.
 *
 * <p>Counting loads is worth one note. Only jobs that actually reached the board count, which means
 * gate refusals are not failures of the image. A job refused because somebody else held the board
 * says nothing about whether the image is good, and letting it block a promotion would make the
 * queue a lottery.
 */
public final class Evidence {

    /** The failure codes of jobs that never reached the board: refused by the gates, or abandoned. */
    private static final Set<String> GATE_CODES = Set.of("gate", "abandoned");

    private Evidence() {}

    /**
     * Whether this image closed timing, and by how much.
     *
     * <p>Empty when no report has been read for it, which is a different answer from a report saying
     * it failed, and the gates treat it that way.
     */
    public static Optional<TimingClosure> timingFor(BaselineBook baselines, String digest) {
        BuildReport report;
        try {
            report = baselines.reportFor(digest);
        } catch (NotFound unreported) {
            return Optional.empty();
        }
        return Optional.of(
                new TimingClosure(report.timing().met(), report.timing().worstSlackPs()));
    }

    /** How many loads of this image passed, and how many failed on a board. */
    public static LoadCounts loadCounts(JobStore jobs, String digest) {
        int passed = 0;
        int failed = 0;
        for (ProgramJob job : jobs.all()) {
            if (!job.digest().equals(digest)) {
                continue;
            }
            if (job.succeeded()) {
                passed++;
            } else if (job.state() == JobState.FAILED && reachedTheBoard(job)) {
                failed++;
            }
        }
        return new LoadCounts(passed, failed);
    }

    /** Everything the bench knows about one image, with no provenance log and no approvals. */
    public static PromotionEvidence evidenceFor(String digest, BaselineBook baselines, JobStore jobs) {
        return evidenceFor(digest, baselines, jobs, Optional.empty(), List.of());
    }

    /** Everything the bench knows about one image, in one object; repeated approvals count once. */
    public static PromotionEvidence evidenceFor(
            String digest,
            BaselineBook baselines,
            JobStore jobs,
            ProvenanceLog provenance,
            Collection<String> approvals) {
        return evidenceFor(digest, baselines, jobs, Optional.of(provenance), approvals);
    }

    private static PromotionEvidence evidenceFor(
            String digest,
            BaselineBook baselines,
            JobStore jobs,
            Optional<ProvenanceLog> provenance,
            Collection<String> approvals) {
        Optional<TimingClosure> timing = timingFor(baselines, digest);
        LoadCounts loads = loadCounts(jobs, digest);
        List<Provenance> builds = provenance.map(log -> log.forDigest(digest)).orElse(List.of());
        List<String> disagreements =
                provenance.map(log -> log.disagreements(digest)).orElse(List.of());
        return PromotionEvidence.builder()
                .timingClosed(timing.map(TimingClosure::closed))
                .worstSlackPs(timing.map(TimingClosure::worstSlackPs))
                .successfulLoads(loads.passed())
                .failedLoads(loads.failed())
                .approvals(List.copyOf(new TreeSet<>(approvals)))
                .provenance(builds)
                .disagreements(disagreements)
                .build();
    }

    private static boolean reachedTheBoard(ProgramJob job) {
        return !GATE_CODES.contains(job.errorCode());
    }
}
