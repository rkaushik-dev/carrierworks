package io.github.rkaushikdev.carrierworks.services;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.artifacts.ArtifactRecord;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.reports.BuildReport;
import io.github.rkaushikdev.carrierworks.reports.TimingClosure;
import io.github.rkaushikdev.carrierworks.reports.TimingSummary;
import io.github.rkaushikdev.carrierworks.reports.Utilization;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reading the build's own reports in, and answering what they mean.
 *
 * <p>The bench never runs synthesis, so everything it knows about a design's timing arrives as two
 * text files somebody uploads beside the image. This is where they stop being text: parsed once,
 * stored as numbers, and after that read by the promotion gate and the regression report without
 * either of them knowing what a Vivado report looks like.
 *
 * <p>Regressions are computed against the design's baseline rather than against the previous build.
 * The baseline is the build somebody was happy with, which is a different thing from the last one,
 * and comparing against the last one hides a slow slide where every build is slightly worse than
 * the one before it.
 */
public final class Reports {

    private Reports() {}

    /**
     * Parse a build's two reports and store the numbers against its image.
     *
     * <p>The first build of a design to close timing becomes its baseline, so a design always has
     * something to be compared with once one build of it was good.
     *
     * @throws io.github.rkaushikdev.carrierworks.NotFound when the image is not known
     * @throws ValidationError when the image already has its reports read, or a report does not
     *     parse
     */
    public static BuildReport ingest(
            UnitOfWork unit, String digest, Instant now, String timingText, String utilizationText) {
        ArtifactRecord record = unit.artifacts().get(digest);
        if (unit.reports().forDigest(digest).isPresent()) {
            throw new ValidationError(
                    "that image already has its reports read", "digest", Details.of("digest", record.shortDigest()));
        }
        BuildReport report = new BuildReport(
                digest, record.design(), now, TimingSummary.parse(timingText), Utilization.parse(utilizationText));
        unit.reports().add(report, now);
        if (unit.reports().baseline(record.design()).isEmpty() && report.closed()) {
            unit.flush();
            unit.reports().setBaseline(digest);
        }
        return report;
    }

    /** Whether an image closed timing and by how much, or nothing when no report has been read. */
    public static Optional<TimingClosure> timingFor(UnitOfWork unit, String digest) {
        return unit.reports()
                .forDigest(digest)
                .map(report ->
                        new TimingClosure(report.timing().met(), report.timing().worstSlackPs()));
    }

    /** The build pinned as a design's baseline, if one is. */
    public static Optional<BuildReport> baseline(UnitOfWork unit, String design) {
        return unit.reports().baseline(design);
    }

    /**
     * Pin a design's baseline to a build somebody was happy with.
     *
     * @throws NotFound when no report has been read for that image
     */
    public static BuildReport setBaseline(UnitOfWork unit, String digest) {
        if (unit.reports().forDigest(digest).isEmpty()) {
            throw new NotFound("build report", digest);
        }
        return unit.reports().setBaseline(digest);
    }

    /**
     * How a build compares with its design's baseline, in words; nothing when it has no baseline or
     * is the baseline.
     *
     * @throws NotFound when no report has been read for that image
     */
    public static List<String> regressions(UnitOfWork unit, String digest) {
        BuildReport report = unit.reports().forDigest(digest).orElseThrow(() -> new NotFound("build report", digest));
        return unit.reports()
                .baseline(report.design())
                .filter(against -> !against.digest().equals(digest))
                .map(against -> BuildReport.compare(against, report))
                .orElse(List.of());
    }
}
