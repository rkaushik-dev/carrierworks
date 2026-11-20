package io.github.rkaushikdev.carrierworks.reports;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.Measure;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.util.Numbers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One build's reports, tied to the image they describe.
 *
 * @param digest the full SHA-256 of the image, as 64 hex digits
 * @param design the design, as a design name
 * @param at when the build ran
 * @param timing what the timing report said
 * @param utilization what the utilization report said
 * @param revision the source revision it was built from, or empty
 */
public record BuildReport(
        String digest, String design, Instant at, TimingSummary timing, Utilization utilization, String revision) {

    // Below these, a difference is placement noise rather than a regression.
    private static final long SLACK_NOISE_PS = 50;
    private static final double UTILISATION_NOISE = 2.0;

    private static final int DIGEST_LENGTH = 64;
    private static final int SHORT_DIGEST_LENGTH = 12;

    public BuildReport {
        design = Ids.designName(design);
        if (digest.length() != DIGEST_LENGTH) {
            throw new ValidationError("a report carries a full digest", "digest");
        }
    }

    /** A report with no source revision recorded. */
    public BuildReport(String digest, String design, Instant at, TimingSummary timing, Utilization utilization) {
        this(digest, design, at, timing, utilization, "");
    }

    /** The first twelve hex digits of the digest, which is how a person reads one. */
    public String shortDigest() {
        return digest.substring(0, SHORT_DIGEST_LENGTH);
    }

    /** Whether the build closed timing. */
    public boolean closed() {
        return timing.met();
    }

    /** {@code radar_frontend aaaaaaaaaaaa: closed: wns 0.400 ns, whs 0.100 ns, 0 failing}. */
    public String describe() {
        return design + " " + shortDigest() + ": " + timing.describe();
    }

    /**
     * Every way the candidate is worse than the baseline, in words.
     *
     * <p>A design closing timing is not the interesting question after the first week. The
     * interesting question is whether it closed by less than it used to, because a design whose
     * worst slack has fallen from four hundred picoseconds to twenty still passes and is one commit
     * away from not passing.
     *
     * <p>The thresholds are here rather than in the caller. Slack falling by under fifty picoseconds
     * is noise from a different placement seed; a row growing by under two percentage points is
     * somebody adding a debug register. A row either report does not have is skipped.
     *
     * @throws ValidationError when the two reports are for different designs
     */
    public static List<String> compare(BuildReport baseline, BuildReport candidate) {
        if (!baseline.design.equals(candidate.design)) {
            throw new ValidationError(
                    "those two reports are for different designs",
                    "design",
                    Details.of("baseline", baseline.design, "candidate", candidate.design));
        }
        List<String> found = new ArrayList<>();
        long before = baseline.timing.worstSlackPs();
        long after = candidate.timing.worstSlackPs();
        if (before - after > SLACK_NOISE_PS) {
            found.add("worst slack fell from " + Measure.renderNs(before) + " to " + Measure.renderNs(after) + " ns");
        }
        int failingBefore = baseline.timing.failingEndpoints();
        int failingAfter = candidate.timing.failingEndpoints();
        if (failingAfter > failingBefore) {
            found.add("failing endpoints went from " + failingBefore + " to " + failingAfter);
        }
        if (baseline.closed() && !candidate.closed()) {
            found.add("this build does not close timing and the baseline did");
        }
        for (SiteKind kind : SiteKind.values()) {
            Optional<SiteUsage> was = baseline.utilization.row(kind);
            Optional<SiteUsage> now = candidate.utilization.row(kind);
            if (was.isEmpty() || now.isEmpty()) {
                continue;
            }
            double growth = now.get().percent() - was.get().percent();
            if (growth > UTILISATION_NOISE) {
                found.add(now.get().name() + " went from "
                        + Numbers.fixed(was.get().percent(), 1) + "% to "
                        + Numbers.fixed(now.get().percent(), 1) + "%");
            }
        }
        return List.copyOf(found);
    }
}
