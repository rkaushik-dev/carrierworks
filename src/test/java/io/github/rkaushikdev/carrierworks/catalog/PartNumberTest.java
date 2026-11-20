package io.github.rkaushikdev.carrierworks.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.JunctionRange;
import io.github.rkaushikdev.carrierworks.ValidationError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PartNumberTest {

    @Test
    void sevenSeriesArtixComesApart() {
        PartNumber part = PartNumber.parse("XC7A100T-2FGG484I");
        assertThat(part.device()).isEqualTo("xc7a100t");
        assertThat(part.speedGrade()).isEqualTo("2");
        assertThat(part.packageCode()).isEqualTo("fgg484");
        assertThat(part.temperature()).isEqualTo("i");
        assertThat(part.family()).isEqualTo("artix7");
        assertThat(part.pinCount()).isEqualTo(484);
    }

    @Test
    void parsingToleratesLowerCaseAndStraySpaces() {
        assertThat(PartNumber.parse("  xc7k325t-2ffg900c ")).isEqualTo(PartNumber.parse("XC7K325T-2FFG900C"));
    }

    @ParameterizedTest
    @CsvSource({
        "XC7A35T-1CPG236C, artix7",
        "XC7K70T-1FBG484C, kintex7",
        "XC7V585T-2FFG1157C, virtex7",
        "XC7S25-1CSGA324C, spartan7",
        "XC7Z020-1CLG484C, zynq7000",
        "XCKU040-2FFVA1156E, kintex_ultrascale",
        "XCVU095-2FFVC2104E, virtex_ultrascale",
        "XCZU9EG-2FFVB1156I, zynq_ultrascale",
        "XC6SLX45-3CSG324C, spartan6",
    })
    void familiesLandWhereTheyShould(String code, String family) {
        assertThat(PartNumber.parse(code).family()).isEqualTo(family);
    }

    @Test
    void orderingCodeRoundTripsInUpperCase() {
        assertThat(PartNumber.parse("xc7a200t-3fbg676c").orderingCode()).isEqualTo("XC7A200T-3FBG676C");
        assertThat(PartNumber.parse("xc7a200t-3fbg676c")).hasToString("XC7A200T-3FBG676C");
    }

    @Test
    void lowPowerScreenIsVisible() {
        PartNumber part = PartNumber.parse("XC7A100T-1LFGG484I");
        assertThat(part.lowPower()).isTrue();
        assertThat(part.speedGrade()).isEqualTo("1l");
        assertThat(PartNumber.parse("XC7A100T-1FGG484I").lowPower()).isFalse();
    }

    @Test
    void speedRankPutsLowPowerBetweenGrades() {
        PartNumber slow = PartNumber.parse("XC7A100T-1FGG484I");
        PartNumber lowPowerTwo = PartNumber.parse("XC7A100T-2LFGG484I");
        PartNumber fast = PartNumber.parse("XC7A100T-2FGG484I");
        assertThat(slow.speedRank()).isLessThan(lowPowerTwo.speedRank());
        assertThat(lowPowerTwo.speedRank()).isLessThan(fast.speedRank());
    }

    @Test
    void aFasterPartStandsInForASlowerOne() {
        PartNumber fast = PartNumber.parse("XC7A100T-3FGG484I");
        PartNumber slow = PartNumber.parse("XC7A100T-1FGG484I");
        assertThat(fast.fits(slow)).isTrue();
        assertThat(slow.fits(fast)).isFalse();
    }

    @Test
    void aDifferentPackageNeverStandsIn() {
        assertThat(PartNumber.parse("XC7A100T-2FGG484I").fits(PartNumber.parse("XC7A100T-2CSG324I")))
                .isFalse();
    }

    @Test
    void aDifferentDeviceNeverStandsIn() {
        assertThat(PartNumber.parse("XC7A200T-2FBG676C").fits(PartNumber.parse("XC7A100T-2FGG484C")))
                .isFalse();
    }

    @Test
    void junctionRangeComesFromTheTemperatureGrade() {
        assertThat(PartNumber.parse("XC7A100T-2FGG484I").junctionRange()).isEqualTo(new JunctionRange(-40, 100));
        assertThat(PartNumber.parse("XC7A100T-2FGG484C").junctionRange()).isEqualTo(new JunctionRange(0, 85));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "   ",
                "XC7A100T",
                "XC7A100T-2FGG484",
                "XC7A100T-FGG484I",
                "EP4CE22F17C6N",
                "XC7A100T-2FGG484X"
            })
    void junkIsRefused(String code) {
        assertThatThrownBy(() -> PartNumber.parse(code)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aHandBuiltPartStillChecksItself() {
        assertThatThrownBy(() -> new PartNumber("xc7a100t", "2", "fgg484", "z")).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new PartNumber("lfe5u45", "6", "bg381", "c")).isInstanceOf(ValidationError.class);
    }

    @Test
    void vendorIsStatedNotGuessed() {
        assertThat(PartNumber.parse("XC7A100T-2FGG484I").vendor()).isEqualTo("xilinx");
    }
}
