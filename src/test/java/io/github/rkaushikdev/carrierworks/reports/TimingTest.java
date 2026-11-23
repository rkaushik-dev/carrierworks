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

    /** The summary the way the place and route log prints it: columns under headings, no bars. */
    private static final String LOGGED =
            """
            ------------------------------------------------------------------------------------------------
            | Design Timing Summary
            | ---------------------
            ------------------------------------------------------------------------------------------------

                WNS(ns)      TNS(ns)  TNS Failing Endpoints  TNS Total Endpoints      WHS(ns)      THS(ns)  \
            THS Failing Endpoints  THS Total Endpoints     WPWS(ns)     TPWS(ns)  TPWS Failing Endpoints  TPWS Total Endpoints
                -------      -------  ---------------------  -------------------      -------      -------  \
            ---------------------  -------------------     --------     --------  ----------------------  --------------------
                 -0.088       -0.351                      6                 9412        0.052        0.000  \
                                0                 9412        3.750        0.000                       0                  4187


            All user specified timing constraints are met.
            """;

    @Test
    void theSummaryIsReadTheWayTheLogPrintsIt() {
        TimingSummary summary = TimingSummary.parse(LOGGED);
        assertThat(summary.wnsPs()).isEqualTo(-88);
        assertThat(summary.tnsPs()).isEqualTo(-351);
        assertThat(summary.setupFailing()).isEqualTo(6);
        assertThat(summary.setupTotal()).isEqualTo(9412);
        assertThat(summary.whsPs()).isEqualTo(52);
        assertThat(summary.holdTotal()).isEqualTo(9412);
        assertThat(summary.setupMet()).isFalse();
    }

    @Test
    void aRowOfTwelveCellsBetweenBarsIsASummaryToo() {
        String row = "| 0.147 | 0.000 | 0 | 100 | 0.021 | 0.000 | 0 | 100 | 3.750 | 0.000 | 0 | 40 |";
        assertThat(TimingSummary.parse(row).whsPs()).isEqualTo(21);
    }

    @Test
    void aRowThatIsNotEightOrTwelveCellsIsNotASummary() {
        assertThat(TimingSummary.summaryCells("0.147 0.000 0 100 0.021 0.000 0 100 9"))
                .isEmpty();
        assertThat(TimingSummary.summaryCells("0.147 0.000 0 100 0.021 0.000 0"))
                .isEmpty();
        assertThat(TimingSummary.summaryCells("0.147 0.000 0 100 0.021 0.000 0 100"))
                .hasValueSatisfying(cells -> assertThat(cells).hasSize(8));
    }

    @Test
    void aCountWhereASlackBelongsIsNotASummary() {
        assertThat(TimingSummary.summaryCells("| 0.147 | 0.000 | 0.5 | 100 | 0.021 | 0.000 | 0 | 100 |"))
                .isEmpty();
        assertThat(TimingSummary.summaryCells("| WNS(ns) | TNS(ns) | a | b | c | d | e | f |"))
                .isEmpty();
        assertThat(TimingSummary.summaryCells("sys_clk 0.147 0.000 0 100 0.021 0.000 0"))
                .isEmpty();
    }

    @Test
    void thePulseWidthGroupIsReadWhenTheLogPrintsIt() {
        PulseWidth pulse = TimingSummary.parse(LOGGED).pulseWidth();
        assertThat(pulse).isEqualTo(new PulseWidth(3750, 0, 0, 4187));
        assertThat(pulse.met()).isTrue();
    }

    @Test
    void anEightCellSummaryHasNoPulseWidthToFail() {
        TimingSummary summary = TimingSummary.parse(REPORT);
        assertThat(summary.pulseWidth()).isEqualTo(PulseWidth.NOT_REPORTED);
        assertThat(summary.met()).isTrue();
    }

    @Test
    void aFailingPulseWidthMeansTheDesignDidNotClose() {
        String row = "| 0.147 | 0.000 | 0 | 100 | 0.021 | 0.000 | 0 | 100 | -0.250 | -0.500 | 2 | 40 |";
        TimingSummary summary = TimingSummary.parse(row);
        assertThat(summary.setupMet()).isTrue();
        assertThat(summary.holdMet()).isTrue();
        assertThat(summary.met()).isFalse();
        assertThat(summary.failingEndpoints()).isEqualTo(2);
        assertThat(summary.worstSlackPs()).isEqualTo(21);
        assertThat(summary.describe())
                .isEqualTo("did not close: wns 0.147 ns, whs 0.021 ns, wpws -0.250 ns, 2 failing");
    }

    @Test
    void aNegativeWorstPulseWidthSlackFailsEvenWithNoCountedFailures() {
        assertThat(new PulseWidth(-1, 0, 0, 10).met()).isFalse();
    }

    @Test
    void aPulseWidthGroupThatDoesNotAddUpIsRefused() {
        assertThatThrownBy(() -> new PulseWidth(0, 0, 3, 2)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new PulseWidth(0, 0, -1, 2)).isInstanceOf(ValidationError.class);
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
