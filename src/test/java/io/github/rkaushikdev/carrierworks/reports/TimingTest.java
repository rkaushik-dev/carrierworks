package io.github.rkaushikdev.carrierworks.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import org.junit.jupiter.api.Test;

class TimingTest {

    private static final String REPORT =
            """

            Design Timing Summary
            Design : radar_frontend
            ---------------------------------------------------------------------------

            | WNS(ns) | TNS(ns) | TNS Failing Endpoints | TNS Total Endpoints | WHS(ns) | THS(ns) | THS Failing Endpoints | THS Total Endpoints |
            | 0.147   | 0.000   | 0                     | 118423              | 0.021   | 0.000   | 0                     | 118423              |

            Clock Summary
            Clock            Waveform(ns)       Period(ns)      Frequency(MHz)
            -----            ------------       ----------      --------------
            sys_clk          {0.000 5.000}      10.000          100.000
            adc_clk          {0.000 2.000}      4.000           250.000
            """;

    private static final String FAILING =
            """

            Design Timing Summary
            | WNS(ns) | TNS(ns)  | TNS Failing Endpoints | TNS Total Endpoints | WHS(ns) | THS(ns) | THS Failing Endpoints | THS Total Endpoints |
            | -0.412  | -18.220  | 143                   | 118423              | 0.021   | 0.000   | 0                     | 118423              |
            """;

    private static final String HOLD_FAILING =
            """

            | WNS(ns) | TNS(ns) | TNS Failing Endpoints | TNS Total Endpoints | WHS(ns) | THS(ns)  | THS Failing Endpoints | THS Total Endpoints |
            | 0.900   | 0.000   | 0                     | 118423              | -0.031  | -1.402   | 47                    | 118423              |
            """;

    @Test
    void aCleanReportReadsAsClosed() {
        TimingSummary summary = TimingSummary.parse(REPORT);
        assertThat(summary.met()).isTrue();
        assertThat(summary.setupMet()).isTrue();
        assertThat(summary.holdMet()).isTrue();
        assertThat(summary.failingEndpoints()).isZero();
    }

    @Test
    void theSlacksComeThroughAsPicoseconds() {
        TimingSummary summary = TimingSummary.parse(REPORT);
        assertThat(summary.wnsPs()).isEqualTo(147);
        assertThat(summary.whsPs()).isEqualTo(21);
        assertThat(summary.tnsPs()).isZero();
    }

    @Test
    void theDesignNameIsPickedUpWhenTheToolPrintsIt() {
        assertThat(TimingSummary.parse(REPORT).design()).isEqualTo("radar_frontend");
        assertThat(TimingSummary.parse(FAILING).design()).isEmpty();
    }

    @Test
    void theClocksAreReadWithTheirPeriods() {
        TimingSummary summary = TimingSummary.parse(REPORT);
        assertThat(summary.clocks().stream().map(ClockConstraint::name)).containsExactly("sys_clk", "adc_clk");
        assertThat(summary.clocks().getFirst().periodPs()).isEqualTo(10_000);
        ClockConstraint fastest = summary.fastestClock().orElseThrow();
        assertThat(fastest.name()).isEqualTo("adc_clk");
        assertThat(fastest.describe()).isEqualTo("adc_clk 250.000 MHz");
    }

    @Test
    void aReportWithNoClockTableHasNoClocks() {
        TimingSummary summary = TimingSummary.parse(FAILING);
        assertThat(summary.clocks()).isEmpty();
        assertThat(summary.fastestClock()).isEmpty();
    }

    @Test
    void aSetupFailureIsSeen() {
        TimingSummary summary = TimingSummary.parse(FAILING);
        assertThat(summary.met()).isFalse();
        assertThat(summary.setupMet()).isFalse();
        assertThat(summary.holdMet()).isTrue();
        assertThat(summary.wnsPs()).isEqualTo(-412);
        assertThat(summary.setupFailing()).isEqualTo(143);
    }

    @Test
    void aHoldFailureIsSeenEvenWhenSetupLooksFine() {
        TimingSummary summary = TimingSummary.parse(HOLD_FAILING);
        assertThat(summary.setupMet()).isTrue();
        assertThat(summary.holdMet()).isFalse();
        assertThat(summary.met()).isFalse();
        assertThat(summary.whsPs()).isEqualTo(-31);
    }

    @Test
    void theWorstSlackIsTheWorseOfTheTwo() {
        assertThat(TimingSummary.parse(HOLD_FAILING).worstSlackPs()).isEqualTo(-31);
        assertThat(TimingSummary.parse(REPORT).worstSlackPs()).isEqualTo(21);
    }

    @Test
    void failingEndpointsAddUpAcrossBoth() {
        TimingSummary summary = TimingSummary.parse(HOLD_FAILING);
        assertThat(summary.failingEndpoints()).isEqualTo(47);
    }

    @Test
    void aReportWithNoSummaryIsRefused() {
        assertThatThrownBy(() -> TimingSummary.parse("Design Timing Summary\nnothing to see here\n"))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void theFirstSummaryRowWinsWhenAReportHoldsSeveral() {
        String doubled = REPORT + FAILING;
        assertThat(TimingSummary.parse(doubled).wnsPs()).isEqualTo(147);
    }

    @Test
    void aRowWithoutTheLeadingBarStillParses() {
        String plain = "0.147 | 0.000 | 0 | 100 | 0.021 | 0.000 | 0 | 100";
        assertThat(TimingSummary.parse(plain).wnsPs()).isEqualTo(147);
    }

    @Test
    void aSummaryThatClaimsMoreFailuresThanEndpointsIsRefused() {
        assertThatThrownBy(() -> new TimingSummary(-100, -100, 10, 5, 0, 0, 0, 5))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void negativeEndpointCountsAreRefused() {
        assertThatThrownBy(() -> new TimingSummary(0, 0, -1, 5, 0, 0, 0, 5)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aSummaryDescribesItself() {
        assertThat(TimingSummary.parse(REPORT).describe())
                .isEqualTo("radar_frontend closed: wns 0.147 ns, whs 0.021 ns, 0 failing");
        assertThat(TimingSummary.parse(FAILING).describe()).contains("did not close");
    }
}
