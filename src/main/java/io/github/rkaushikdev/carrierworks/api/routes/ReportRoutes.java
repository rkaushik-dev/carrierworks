package io.github.rkaushikdev.carrierworks.api.routes;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.Role;
import io.github.rkaushikdev.carrierworks.api.Errors;
import io.github.rkaushikdev.carrierworks.api.Requires;
import io.github.rkaushikdev.carrierworks.api.Work;
import io.github.rkaushikdev.carrierworks.reports.BuildReport;
import io.github.rkaushikdev.carrierworks.services.Reports;
import io.github.rkaushikdev.carrierworks.util.Times;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The report routes: reading a build's numbers in and asking what they mean. */
@RestController
public class ReportRoutes {

    private static final String KIND = "build report";

    private final Work work;
    private final Clock clock;

    public ReportRoutes(Work work, Clock clock) {
        this.work = work;
        this.clock = clock;
    }

    /**
     * A build's two reports, as the tools wrote them.
     *
     * @param timing the timing summary
     * @param utilization the utilization table
     */
    public record ReportsIn(
            @NotNull(message = Errors.REQUIRED) String timing,
            @NotNull(message = Errors.REQUIRED) String utilization) {}

    /**
     * A design's baseline being pinned.
     *
     * @param digest the image whose build it should be measured against from now on
     */
    public record BaselineIn(@NotNull(message = Errors.REQUIRED) String digest) {}

    /**
     * What a build's reports said.
     *
     * @param digest the image the build produced
     * @param design the design
     * @param closed whether it met timing
     * @param wnsPs the worst setup slack, in picoseconds
     * @param whsPs the worst hold slack, in picoseconds
     * @param worstSlackPs the worse of the two
     * @param failingEndpoints how many endpoints missed
     * @param luts the lookup tables used
     * @param registers the registers used
     * @param blockRam the block memories used
     * @param dsp the multipliers used
     */
    public record ReportOut(
            String digest,
            String design,
            boolean closed,
            long wnsPs,
            long whsPs,
            long worstSlackPs,
            int failingEndpoints,
            int luts,
            int registers,
            int blockRam,
            int dsp) {

        /** One build's numbers. */
        public static ReportOut of(BuildReport report) {
            return new ReportOut(
                    report.digest(),
                    report.design(),
                    report.closed(),
                    report.timing().wnsPs(),
                    report.timing().whsPs(),
                    report.timing().worstSlackPs(),
                    report.timing().failingEndpoints(),
                    report.utilization().luts(),
                    report.utilization().registers(),
                    report.utilization().blockRam(),
                    report.utilization().dsp());
        }
    }

    /**
     * How one build compares with the one its design is measured against.
     *
     * @param digest the image the build produced
     * @param regressions what got worse, in words
     * @param clean whether nothing did
     */
    public record RegressionsOut(String digest, List<String> regressions, boolean clean) {

        public RegressionsOut {
            regressions = List.copyOf(regressions);
        }
    }

    /** Read a build's timing and utilization reports against its image. */
    @PostMapping("/artifacts/{digest}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    @Requires(Role.OPERATOR)
    public ReportOut ingestReports(@PathVariable String digest, @Valid @RequestBody ReportsIn reports) {
        Instant moment = Times.now(clock);
        return work.apply(
                unit -> ReportOut.of(Reports.ingest(unit, digest, moment, reports.timing(), reports.utilization())));
    }

    /** What the build said about one image. */
    @GetMapping("/artifacts/{digest}/reports")
    @Requires(Role.READER)
    public ReportOut readReports(@PathVariable String digest) {
        return work.apply(
                unit -> ReportOut.of(unit.reports().forDigest(digest).orElseThrow(() -> new NotFound(KIND, digest))));
    }

    /** How this build compares with the one its design is measured against. */
    @GetMapping("/artifacts/{digest}/regressions")
    @Requires(Role.READER)
    public RegressionsOut readRegressions(@PathVariable String digest) {
        return work.apply(unit -> {
            List<String> found = Reports.regressions(unit, digest);
            return new RegressionsOut(digest, found, found.isEmpty());
        });
    }

    /** The build a design is measured against, or {@code null} when none has been pinned. */
    @GetMapping("/designs/{design}/baseline")
    @Requires(Role.READER)
    public Optional<ReportOut> readBaseline(@PathVariable String design) {
        return work.apply(unit -> Reports.baseline(unit, design).map(ReportOut::of));
    }

    /** Pin a design's baseline to a build somebody was happy with, which has to be a build of that design. */
    @PostMapping("/designs/{design}/baseline")
    @Requires(Role.ADMIN)
    public ReportOut pinBaseline(@PathVariable String design, @Valid @RequestBody BaselineIn pinned) {
        return work.apply(unit -> {
            BuildReport report = Reports.setBaseline(unit, pinned.digest());
            if (!report.design().equals(design.strip().toLowerCase(Locale.ROOT))) {
                throw new NotFound(KIND, pinned.digest());
            }
            return ReportOut.of(report);
        });
    }
}
