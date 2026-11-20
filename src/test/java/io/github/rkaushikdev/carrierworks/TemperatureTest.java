package io.github.rkaushikdev.carrierworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.catalog.PartNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TemperatureTest {

    private static ThermalLimits limits() {
        return new ThermalLimits(76_000, 86_000, 90_000);
    }

    @ParameterizedTest
    @CsvSource({"62, 62000", "62.5, 62500", "62.5C, 62500", "62.5 degC, 62500", "-40, -40000", "-0.5, -500"})
    void temperaturesReadHoweverTheyWereWritten(String text, int millidegrees) {
        assertThat(Temperature.parse(text)).isEqualTo(millidegrees);
    }

    @Test
    void numbersComeThroughAsThemselves() {
        assertThat(Temperature.parse(62)).isEqualTo(62_000);
        assertThat(Temperature.parse(62.5)).isEqualTo(62_500);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "warm", "62.5555", "sixty"})
    void junkTemperaturesAreRefused(String text) {
        assertThatThrownBy(() -> Temperature.parse(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void renderingGivesOneDecimal() {
        assertThat(Temperature.render(62_500)).isEqualTo("62.5C");
        assertThat(Temperature.render(-40_000)).isEqualTo("-40.0C");
    }

    @Test
    void theSystemMonitorFormulaMatchesTheUserGuide() {
        assertThat(Temperature.fromXadcCode(0x0000)).isEqualTo(-273_150);
        assertThat(Temperature.fromXadcCode(0x0800)).isEqualTo(251_987 - 273_150);
        assertThat(Temperature.fromXadcCode(0x0FFF)).isEqualTo(230_701);
    }

    @Test
    void aRealisticCodeLandsAtARealisticTemperature() {
        int reading = Temperature.fromXadcCode(0x09C4);
        assertThat(reading).isStrictlyBetween(34_000, 36_000);
    }

    @Test
    void codesRoundTripWithinACount() {
        for (int code : new int[] {0x0800, 0x0900, 0x0A50, 0x0B00}) {
            assertThat(Math.abs(Temperature.toXadcCode(Temperature.fromXadcCode(code)) - code))
                    .isLessThanOrEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0x1000})
    void codesOutsideTwelveBitsAreRefused(int code) {
        assertThatThrownBy(() -> Temperature.fromXadcCode(code)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aTemperatureTheSensorCannotReportIsRefused() {
        assertThatThrownBy(() -> Temperature.toXadcCode(500_000)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> Temperature.toXadcCode(-300_000)).isInstanceOf(ValidationError.class);
    }

    @Test
    void limitsHaveToClimb() {
        assertThatThrownBy(() -> new ThermalLimits(90_000, 86_000, 95_000)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new ThermalLimits(10_000, 20_000, 30_000, 0)).isInstanceOf(ValidationError.class);
    }

    @Test
    void limitsComeOffThePartTemperatureGrade() {
        PartNumber part = PartNumber.parse("XC7A100T-2FGG484I");
        ThermalLimits industrial = ThermalLimits.fromJunctionRange(part.junctionRange());
        assertThat(industrial.shutdownMc()).isEqualTo(100_000);
        assertThat(industrial.criticalMc()).isEqualTo(96_000);
        assertThat(industrial.warnMc()).isEqualTo(86_000);
    }

    @Test
    void aCommercialPartGetsColderLimitsThanAnIndustrialOne() {
        ThermalLimits commercial = ThermalLimits.fromJunctionRange(new JunctionRange(0, 85));
        ThermalLimits industrial = ThermalLimits.fromJunctionRange(new JunctionRange(-40, 100));
        assertThat(commercial.shutdownMc()).isLessThan(industrial.shutdownMc());
        assertThat(commercial.warnMc()).isLessThan(industrial.warnMc());
    }

    @Test
    void anOverrideSurvivesTheDerivation() {
        ThermalLimits derived =
                ThermalLimits.fromJunctionRange(new JunctionRange(0, 85)).withWarnMc(60_000);
        assertThat(derived.warnMc()).isEqualTo(60_000);
        assertThat(derived.shutdownMc()).isEqualTo(85_000);
    }

    @ParameterizedTest
    @CsvSource({
        "40000, NORMAL",
        "75999, NORMAL",
        "76000, WARN",
        "85999, WARN",
        "86000, CRITICAL",
        "89999, CRITICAL",
        "90000, SHUTDOWN",
        "120000, SHUTDOWN",
    })
    void aColdStartLandsInTheObviousBand(int millidegrees, ThermalState state) {
        assertThat(limits().stateFor(millidegrees)).isEqualTo(state);
    }

    @Test
    void climbingHappensTheMomentTheThresholdIsCrossed() {
        assertThat(limits().nextState(ThermalState.NORMAL, 76_000)).isEqualTo(ThermalState.WARN);
        assertThat(limits().nextState(ThermalState.WARN, 86_000)).isEqualTo(ThermalState.CRITICAL);
        assertThat(limits().nextState(ThermalState.CRITICAL, 90_000)).isEqualTo(ThermalState.SHUTDOWN);
    }

    @Test
    void aJumpSkipsStraightToTheHottestBandItEarns() {
        assertThat(limits().nextState(ThermalState.NORMAL, 91_000)).isEqualTo(ThermalState.SHUTDOWN);
    }

    @Test
    void fallingBackNeedsAWholeHysteresisBand() {
        ThermalLimits live = limits();
        assertThat(live.nextState(ThermalState.CRITICAL, 85_000)).isEqualTo(ThermalState.CRITICAL);
        assertThat(live.nextState(ThermalState.CRITICAL, 83_001)).isEqualTo(ThermalState.CRITICAL);
        assertThat(live.nextState(ThermalState.CRITICAL, 83_000)).isEqualTo(ThermalState.WARN);
    }

    @Test
    void itCanFallThroughTwoBandsAtOnceWhenItCoolsProperly() {
        assertThat(limits().nextState(ThermalState.CRITICAL, 60_000)).isEqualTo(ThermalState.NORMAL);
    }

    @Test
    void sittingOnAThresholdDoesNotChatter() {
        ThermalLimits live = limits();
        ThermalState state = ThermalState.NORMAL;
        for (int reading : new int[] {76_000, 75_900, 76_100, 75_800, 76_000}) {
            state = live.nextState(state, reading);
            assertThat(state).isEqualTo(ThermalState.WARN);
        }
    }

    @Test
    void anUnknownStateGoingInIsRefused() {
        assertThatThrownBy(() -> limits().nextState(ThermalState.UNKNOWN, 40_000))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> ThermalState.parse("toasty")).isInstanceOf(ValidationError.class);
    }

    @Test
    void askingForTheThresholdOfNormalIsABug() {
        assertThatThrownBy(() -> limits().thresholdFor(ThermalState.NORMAL)).isInstanceOf(ValidationError.class);
    }
}
