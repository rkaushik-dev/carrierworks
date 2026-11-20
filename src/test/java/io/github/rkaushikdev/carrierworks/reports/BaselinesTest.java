package io.github.rkaushikdev.carrierworks.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BaselinesTest {

    private static final Instant NOW = Instant.parse("2025-11-08T03:00:00Z");

    private static TimingSummary timing(long wns, long whs, int failing) {
        return new TimingSummary(wns, failing == 0 ? 0 : -1000, failing, 100_000, whs, 0, 0, 100_000);
    }

    private static TimingSummary timing() {
        return timing(400, 100, 0);
    }

    private static Utilization utilization(int luts, int registers) {
        return new Utilization(List.of(
                new SiteUsage("Slice LUTs", luts, 203800), new SiteUsage("Slice Registers", registers, 407600)));
    }

    private static Utilization utilization() {
        return utilization(45000, 61000);
    }

    /** A build of radar_frontend at {@link #NOW} that closed, with whatever a test changes. */
    private static final class Build {

        private String digest = "aa";
        private String design = "radar_frontend";
        private Instant at = NOW;
        private TimingSummary timing = BaselinesTest.timing();
        private Utilization utilization = BaselinesTest.utilization();

        /** The digest, as a two digit pattern repeated to full length. */
        Build digest(String pattern) {
            digest = pattern;
            return this;
        }

        Build design(String name) {
            design = name;
            return this;
        }

        Build at(Instant moment) {
            at = moment;
            return this;
        }

        Build timing(TimingSummary summary) {
            timing = summary;
            return this;
        }

        Build utilization(Utilization used) {
            utilization = used;
            return this;
        }

        BuildReport build() {
            return new BuildReport(digest.repeat(32), design, at, timing, utilization, "9f2c11a");
        }
    }

    private static Build report() {
        return new Build();
    }

    @Test
    void aReportIsTiedToItsImage() {
        BuildReport built = report().build();
        assertThat(built.shortDigest()).isEqualTo("aa".repeat(6));
        assertThat(built.closed()).isTrue();
        assertThat(built.describe()).startsWith("radar_frontend aaaaaaaaaaaa: ");
    }

    /**
     * A short digest is refused, and so is a build time with no zone. A report keeps its moment as
     * an {@link Instant}, which is always UTC, so a zoneless time cannot be handed in; the test
     * holds the type to that.
     */
    @Test
    void aReportNeedsAWholeDigestAndATimezone() {
        assertThatThrownBy(() -> new BuildReport("abcdef", "radar_frontend", NOW, timing(), utilization()))
                .isInstanceOf(ValidationError.class);
        assertThat(Arrays.stream(BuildReport.class.getRecordComponents())
                        .filter(component -> component.getName().equals("at"))
                        .<Object>map(component -> component.getType()))
                .containsExactly(Instant.class);
    }

    @Test
    void twoIdenticalBuildsDoNotRegress() {
        assertThat(BuildReport.compare(report().build(), report().digest("bb").build()))
                .isEmpty();
    }

    @Test
    void slackNoiseIsNotARegression() {
        BuildReport worse = report().digest("bb").timing(timing(360, 100, 0)).build();
        assertThat(BuildReport.compare(report().build(), worse)).isEmpty();
    }

    @Test
    void aRealSlackDropIsReported() {
        BuildReport worse = report().digest("bb").timing(timing(120, 30, 0)).build();
        List<String> found = BuildReport.compare(report().build(), worse);
        assertThat(found).containsExactly("worst slack fell from 0.100 to 0.030 ns");
    }

    @Test
    void aHoldSlackDropIsReportedToo() {
        BuildReport worse = report().digest("bb").timing(timing(400, -40, 0)).build();
        List<String> found = BuildReport.compare(report().build(), worse);
        assertThat(found).anyMatch(line -> line.contains("slack fell"));
        assertThat(found).anyMatch(line -> line.contains("does not close timing"));
    }

    @Test
    void newFailingEndpointsAreReported() {
        BuildReport worse = report().digest("bb").timing(timing(-100, 100, 17)).build();
        List<String> found = BuildReport.compare(report().build(), worse);
        assertThat(found).anyMatch(line -> line.contains("failing endpoints went from 0 to 17"));
    }

    @Test
    void smallLogicGrowthIsNotARegression() {
        BuildReport grown =
                report().digest("bb").utilization(utilization(47000, 61000)).build();
        assertThat(BuildReport.compare(report().build(), grown)).isEmpty();
    }

    @Test
    void realLogicGrowthIsReported() {
        BuildReport grown =
                report().digest("bb").utilization(utilization(90000, 61000)).build();
        List<String> found = BuildReport.compare(report().build(), grown);
        assertThat(found).anyMatch(line -> line.startsWith("Slice LUTs went from"));
    }

    @Test
    void aRowTheOtherReportDoesNotHaveIsSkipped() {
        BuildReport thin =
                report().digest("bb").utilization(new Utilization(List.of())).build();
        assertThat(BuildReport.compare(report().build(), thin)).isEmpty();
    }

    @Test
    void twoDesignsCannotBeCompared() {
        BuildReport other = report().digest("bb").design("beamformer").build();
        assertThatThrownBy(() -> BuildReport.compare(report().build(), other)).isInstanceOf(ValidationError.class);
    }

    @Test
    void theFirstClosingBuildBecomesTheBaseline() {
        BaselineBook book = new BaselineBook();
        BuildReport first = book.record(report().build());
        assertThat(book.baseline("radar_frontend")).map(BuildReport::digest).contains(first.digest());
    }

    @Test
    void aBuildThatDidNotCloseDoesNotBecomeTheBaseline() {
        BaselineBook book = new BaselineBook();
        book.record(report().timing(timing(-100, 100, 4)).build());
        assertThat(book.baseline("radar_frontend")).isEmpty();
    }

    @Test
    void laterBuildsDoNotMoveTheBaseline() {
        BaselineBook book = new BaselineBook();
        BuildReport first = book.record(report().build());
        book.record(report().digest("bb").at(NOW.plus(Duration.ofDays(1))).build());
        assertThat(book.baseline("radar_frontend")).map(BuildReport::digest).contains(first.digest());
    }

    @Test
    void theBaselineCanBePinnedToABuildSomebodyLiked() {
        BaselineBook book = new BaselineBook();
        book.record(report().build());
        BuildReport later = book.record(
                report().digest("bb").at(NOW.plus(Duration.ofDays(1))).build());
        book.setBaseline("radar_frontend", later.digest());
        assertThat(book.baseline("radar_frontend")).map(BuildReport::digest).contains(later.digest());
    }

    @Test
    void pinningABuildFromAnotherDesignIsRefused() {
        BaselineBook book = new BaselineBook();
        book.record(report().build());
        BuildReport other =
                book.record(report().digest("bb").design("beamformer").build());
        assertThatThrownBy(() -> book.setBaseline("radar_frontend", other.digest()))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void pinningABuildNobodyRecordedIsANotFound() {
        assertThatThrownBy(() -> new BaselineBook().setBaseline("radar_frontend", "cc".repeat(32)))
                .isInstanceOf(NotFound.class);
    }

    @Test
    void regressionsAreReportedAgainstTheBaseline() {
        BaselineBook book = new BaselineBook();
        book.record(report().build());
        BuildReport worse =
                book.record(report().digest("bb").timing(timing(100, 20, 0)).build());
        assertThat(book.regressions(worse)).isNotEmpty();
    }

    @Test
    void aBuildDoesNotRegressAgainstItself() {
        BaselineBook book = new BaselineBook();
        BuildReport first = book.record(report().build());
        assertThat(book.regressions(first)).isEmpty();
    }

    @Test
    void aDesignWithNoBaselineHasNoRegressions() {
        BaselineBook book = new BaselineBook();
        BuildReport only = book.record(report().timing(timing(-100, 100, 4)).build());
        assertThat(book.regressions(only)).isEmpty();
    }

    @Test
    void theBookKeepsEveryReportPerDesign() {
        BaselineBook book = new BaselineBook();
        book.record(report().build());
        book.record(report().digest("bb").at(NOW.plus(Duration.ofHours(1))).build());
        book.record(report().digest("cc").design("beamformer").build());
        assertThat(book.designs()).containsExactly("beamformer", "radar_frontend");
        assertThat(book.reports("radar_frontend")).hasSize(2);
        assertThat(book.size()).isEqualTo(3);
        assertThat(book.latest("radar_frontend")).map(BuildReport::digest).contains("bb".repeat(32));
    }
}
