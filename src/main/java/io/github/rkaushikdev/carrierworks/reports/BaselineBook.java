package io.github.rkaushikdev.carrierworks.reports;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every build report, and which one is the baseline for each design.
 *
 * <p>A baseline is kept per design, and it is set deliberately rather than being whatever ran last.
 * The baseline is the build somebody was happy with, and a build that regresses against it is
 * reported even when it passes on its own terms. Until somebody pins one, a design's first build
 * that closed timing is its baseline.
 */
public final class BaselineBook {

    private final Map<String, List<BuildReport>> reports = new LinkedHashMap<>();
    private final Map<String, String> baselines = new HashMap<>();

    /** Keep a report, and make it the baseline if the design has none and the build closed. */
    public BuildReport record(BuildReport report) {
        reports.computeIfAbsent(report.design(), ignored -> new ArrayList<>()).add(report);
        if (!baselines.containsKey(report.design()) && report.closed()) {
            baselines.put(report.design(), report.digest());
        }
        return report;
    }

    /** Every report for a design, in the order recorded. */
    public List<BuildReport> reports(String design) {
        return List.copyOf(reports.getOrDefault(Ids.designName(design), List.of()));
    }

    /** A design's most recent build by build time, the first recorded on a tie. */
    public Optional<BuildReport> latest(String design) {
        return reports(design).stream().max(Comparator.comparing(BuildReport::at));
    }

    /** The report for one image, or a not found. */
    public BuildReport reportFor(String digest) {
        return reports.values().stream()
                .flatMap(List::stream)
                .filter(report -> report.digest().equals(digest))
                .findFirst()
                .orElseThrow(() -> new NotFound("build report", digest));
    }

    /** The build a design is compared against, if it has one. */
    public Optional<BuildReport> baseline(String design) {
        return Optional.ofNullable(baselines.get(Ids.designName(design))).map(this::reportFor);
    }

    /**
     * Pin a design's baseline to a build somebody was happy with.
     *
     * @throws NotFound when no report for that image was recorded
     * @throws ValidationError when that build is for another design
     */
    public BuildReport setBaseline(String design, String digest) {
        BuildReport report = reportFor(digest);
        String wanted = Ids.designName(design);
        if (!report.design().equals(wanted)) {
            throw new ValidationError(
                    "that build is for another design",
                    "digest",
                    Details.of("design", wanted, "found", report.design()));
        }
        baselines.put(report.design(), digest);
        return report;
    }

    /** How this build compares with its design's baseline; nothing when there is none or it is the build. */
    public List<String> regressions(BuildReport report) {
        Optional<BuildReport> baseline = baseline(report.design());
        if (baseline.isEmpty() || baseline.get().digest().equals(report.digest())) {
            return List.of();
        }
        return BuildReport.compare(baseline.get(), report);
    }

    /** Every design with a report, sorted. */
    public List<String> designs() {
        return reports.keySet().stream().sorted().toList();
    }

    /** How many reports the book holds across every design. */
    public int size() {
        return reports.values().stream().mapToInt(List::size).sum();
    }
}
