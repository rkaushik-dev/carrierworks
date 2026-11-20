package io.github.rkaushikdev.carrierworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ElectricalTest {

    private static RailSpec vccint() {
        return new RailSpec("vccint", 1000, 5, 4000);
    }

    private static RailSpec vadj() {
        return new RailSpec("vadj", 1800, 3, 2000, 3, true);
    }

    @ParameterizedTest
    @CsvSource({"3v3, 3300", "1v8, 1800", "3.3V, 3300", "3.30 V, 3300", "1.0, 1000", "5v, 5000", "0.85V, 850"})
    void voltagesReadHoweverTheLabWroteThem(String text, int millivolts) {
        assertThat(Electrical.parseVoltage(text)).isEqualTo(millivolts);
    }

    @Test
    void anIntegerVoltageIsAlreadyMillivolts() {
        assertThat(Electrical.parseVoltage(1800)).isEqualTo(1800);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "high", "1.2345", "v", "-1.8"})
    void junkVoltagesAreRefused(String text) {
        assertThatThrownBy(() -> Electrical.parseVoltage(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aNegativeIntegerVoltageIsRefused() {
        assertThatThrownBy(() -> Electrical.parseVoltage(-1)).isInstanceOf(ValidationError.class);
    }

    @ParameterizedTest
    @CsvSource({"500, 500", "500ma, 500", "1.5A, 1500", "2 a, 2000", "0.25A, 250"})
    void currentsReadInMilliamps(String text, int milliamps) {
        assertThat(Electrical.parseCurrent(text)).isEqualTo(milliamps);
    }

    @Test
    void aFractionOfAMilliampIsRefused() {
        assertThatThrownBy(() -> Electrical.parseCurrent("0.5ma")).isInstanceOf(ValidationError.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "lots", "-4"})
    void junkCurrentsAreRefused(String text) {
        assertThatThrownBy(() -> Electrical.parseCurrent(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void powerIsWholeMilliwatts() {
        assertThat(Electrical.milliwatts(1000, 4000)).isEqualTo(4000);
        assertThat(Electrical.milliwatts(1800, 250)).isEqualTo(450);
        assertThat(Electrical.milliwatts(1799, 1)).isEqualTo(2);
    }

    @Test
    void powerOfANegativeReadingIsABug() {
        assertThatThrownBy(() -> Electrical.milliwatts(-1000, 10)).isInstanceOf(ValidationError.class);
    }

    @Test
    void theWindowComesFromTheTolerance() {
        RailSpec rail = vccint();
        assertThat(rail.lowLimitMv()).isEqualTo(950);
        assertThat(rail.highLimitMv()).isEqualTo(1050);
        assertThat(rail.contains(950)).isTrue();
        assertThat(rail.contains(1050)).isTrue();
        assertThat(rail.contains(949)).isFalse();
        assertThat(rail.contains(1051)).isFalse();
    }

    @Test
    void theLowLimitRoundsInward() {
        RailSpec rail = new RailSpec("vccaux", 1799, 3, 500);
        assertThat(rail.lowLimitMv()).isEqualTo(1746);
        assertThat(rail.highLimitMv()).isEqualTo(1852);
    }

    @Test
    void deviationIsSignedPartsPerThousand() {
        RailSpec rail = vccint();
        assertThat(rail.deviationPermille(1000)).isZero();
        assertThat(rail.deviationPermille(970)).isEqualTo(-30);
        assertThat(rail.deviationPermille(1020)).isEqualTo(20);
    }

    @Test
    void railNamesAreNormalised() {
        RailSpec rail = new RailSpec("VCC INT", 1000, 5, 1);
        assertThat(rail.name()).isEqualTo("vcc-int");
    }

    @ParameterizedTest
    @CsvSource({"0, 5, 100", "1000, 0, 100", "1000, 30, 100", "1000, 5, 0"})
    void aNonsenseRailIsRefused(int nominal, int tolerance, int current) {
        assertThatThrownBy(() -> new RailSpec("bad", nominal, tolerance, current))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aHealthyReadingHasNoFault() {
        RailReading reading = vccint().reading(998, 3200);
        assertThat(reading.inWindow()).isTrue();
        assertThat(reading.fault()).isEmpty();
        assertThat(reading.powerMw()).isEqualTo(3194);
    }

    @Test
    void aSaggingRailReportsUnderVoltage() {
        RailReading reading = vccint().reading(910, 3800);
        assertThat(reading.sagging()).isTrue();
        assertThat(reading.fault()).contains(RailReading.Fault.UNDER_VOLTAGE);
    }

    @Test
    void anOvershootingRailReportsOverVoltage() {
        RailReading reading = vccint().reading(1100, 100);
        assertThat(reading.sagging()).isFalse();
        assertThat(reading.fault()).contains(RailReading.Fault.OVER_VOLTAGE);
    }

    @Test
    void overCurrentWinsOverAVoltageComplaint() {
        RailReading reading = vccint().reading(910, 4500);
        assertThat(reading.overCurrent()).isTrue();
        assertThat(reading.fault()).contains(RailReading.Fault.OVER_CURRENT);
    }

    @Test
    void maxPowerUsesTheTopOfTheWindow() {
        assertThat(vadj().maxPowerMw()).isEqualTo(3708);
    }

    @Test
    void readingsPrintTheWayPeopleReadThemOut() {
        assertThat(vccint().reading(998, 3200)).hasToString("vccint 0.998V 3200mA");
        assertThat(vadj().reading(1800, 0)).hasToString("vadj 1.8V 0mA");
    }
}
