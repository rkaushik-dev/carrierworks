package io.github.rkaushikdev.carrierworks.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.Devices;
import java.util.List;
import org.junit.jupiter.api.Test;

class UtilizationTest {

    private static final String REPORT =
            """

            Utilization Design Information
            Design : radar_frontend

            1. Slice Logic
            --------------

            +----------------------------+-------+-------+-----------+-------+
            |          Site Type         |  Used | Fixed | Available | Util% |
            +----------------------------+-------+-------+-----------+-------+
            | Slice LUTs                 | 45123 |     0 |    203800 | 22.14 |
            | Slice Registers            | 61234 |     0 |    407600 | 15.02 |
            +----------------------------+-------+-------+-----------+-------+

            3. Memory
            ---------

            +-------------------+------+-------+-----------+-------+
            |     Site Type     | Used | Fixed | Available | Util% |
            +-------------------+------+-------+-----------+-------+
            | Block RAM Tile    |  145 |     0 |       445 | 32.58 |
            +-------------------+------+-------+-----------+-------+

            4. DSP
            ------

            +-----------+------+-------+-----------+-------+
            | Site Type | Used | Fixed | Available | Util% |
            +-----------+------+-------+-----------+-------+
            | DSPs      |  210 |     0 |       840 | 25.00 |
            +-----------+------+-------+-----------+-------+
            """;

    private static final String NEWER =
            """

            +----------------------------+-------+-------+------------+-----------+-------+
            |          Site Type         |  Used | Fixed | Prohibited | Available | Util% |
            +----------------------------+-------+-------+------------+-----------+-------+
            | CLB LUTs                   | 12000 |     0 |          0 |    203800 |  5.89 |
            | CLB Registers              | 18000 |     0 |          0 |    407600 |  4.42 |
            +----------------------------+-------+-------+------------+-----------+-------+
            """;

    private static List<String> names(List<SiteUsage> rows) {
        return rows.stream().map(SiteUsage::name).toList();
    }

    @Test
    void theFourNumbersComeOut() {
        Utilization used = Utilization.parse(REPORT);
        assertThat(used.luts()).isEqualTo(45123);
        assertThat(used.registers()).isEqualTo(61234);
        assertThat(used.blockRam()).isEqualTo(145);
        assertThat(used.dsp()).isEqualTo(210);
    }

    @Test
    void theDesignNameComesThrough() {
        assertThat(Utilization.parse(REPORT).design()).isEqualTo("radar_frontend");
        assertThat(Utilization.parse(NEWER).design()).isEmpty();
    }

    @Test
    void theHeaderRowIsNotASite() {
        List<String> names = names(Utilization.parse(REPORT).rows());
        assertThat(names).doesNotContain("Site Type");
        assertThat(names).hasSize(4);
    }

    @Test
    void aNewerReportWithAProhibitedColumnStillParses() {
        Utilization used = Utilization.parse(NEWER);
        assertThat(used.luts()).isEqualTo(12000);
        assertThat(used.registers()).isEqualTo(18000);
        assertThat(used.blockRam()).isZero();
    }

    @Test
    void percentagesAreRecomputedRatherThanTrusted() {
        SiteUsage row = Utilization.parse(REPORT).row(SiteKind.LUTS).orElseThrow();
        assertThat(row.percent()).isCloseTo(22.14, within(0.01));
    }

    @Test
    void theBusiestRowIsTheOneNearestFull() {
        SiteUsage busiest = Utilization.parse(REPORT).busiest().orElseThrow();
        assertThat(busiest.name()).isEqualTo("Block RAM Tile");
    }

    @Test
    void rowsOverAThresholdComeBack() {
        List<SiteUsage> over = Utilization.parse(REPORT).over(25.0);
        assertThat(names(over)).containsExactly("Block RAM Tile", "DSPs");
    }

    @Test
    void aDesignFitsThePartItWasBuiltFor() {
        Utilization used = Utilization.parse(REPORT);
        assertThat(used.fits(Devices.deviceFor("xc7k325t"))).isTrue();
    }

    @Test
    void theSameDesignDoesNotFitASmallerPart() {
        Utilization used = Utilization.parse(REPORT);
        assertThat(used.fits(Devices.deviceFor("xc7a100t"))).isFalse();
    }

    @Test
    void aSmallDesignFitsASmallPart() {
        Utilization used = Utilization.parse(NEWER);
        assertThat(used.fits(Devices.deviceFor("xc7a100t"))).isTrue();
    }

    @Test
    void aReportWithNoTablesIsRefused() {
        assertThatThrownBy(() -> Utilization.parse("Utilization Design Information\nnothing here\n"))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void askingForAKindNobodyHasIsABug() {
        Utilization used = Utilization.parse(REPORT);
        assertThatThrownBy(() -> used.row(SiteKind.parse("transceivers"))).isInstanceOf(ValidationError.class);
    }

    @Test
    void aRowUsingMoreThanExistsIsRefused() {
        assertThatThrownBy(() -> new SiteUsage("Slice LUTs", 10, 5)).isInstanceOf(ValidationError.class);
    }

    @Test
    void negativeCountsAreRefused() {
        assertThatThrownBy(() -> new SiteUsage("Slice LUTs", -1, 5)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aRowOfNothingAvailableReadsAsZeroPercent() {
        assertThat(new SiteUsage("Unused", 0, 0).percent()).isEqualTo(0.0);
    }

    @Test
    void aRowDescribesItself() {
        SiteUsage row = Utilization.parse(REPORT).row(SiteKind.DSP).orElseThrow();
        assertThat(row.describe()).isEqualTo("DSPs 210/840 (25.0%)");
    }

    @Test
    void theReportDescribesItself() {
        String described = Utilization.parse(REPORT).describe();
        assertThat(described).startsWith("radar_frontend: 45123 luts, 61234 registers");
        assertThat(described).contains("busiest Block RAM Tile");
    }

    @Test
    void anEmptyUtilizationDescribesItselfToo() {
        assertThat(new Utilization(List.of()).describe()).isEqualTo("nothing used");
        assertThat(new Utilization(List.of()).busiest()).isEmpty();
    }
}
