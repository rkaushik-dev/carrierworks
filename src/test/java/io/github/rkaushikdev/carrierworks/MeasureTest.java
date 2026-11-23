package io.github.rkaushikdev.carrierworks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MeasureTest {

    @ParameterizedTest
    @CsvSource({
        "0, 0",
        "4096, 4096",
        "0x800000, 8388608",
        "0X80_0000, 8388608",
        "0o17, 15",
        "0b1010, 10",
        "-16, -16",
        "8MiB, 8388608",
        "' 16 m ', 16777216",
        "64k, 65536"
    })
    void addressesReadAsLiteralsOrSizes(String text, long expected) {
        assertThat(Measure.parseAddress(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0x", "08", "1__0", "0xfffffffffffffffff", "12 parsecs", "_1"})
    void unreadableAddressesAreRefused(String text) {
        assertThatThrownBy(() -> Measure.parseAddress(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void helpersAreBinary() {
        assertThat(Measure.kib(64)).isEqualTo(65536);
        assertThat(Measure.mib(16)).isEqualTo(16777216);
    }

    @ParameterizedTest
    @CsvSource({
        "512, 512",
        "512 B, 512",
        "64 KiB, 65536",
        "64kb, 65536",
        "16 MiB, 16777216",
        "1.5 MiB, 1572864",
        "2 GiB, 2147483648",
    })
    void sizesReadTheWayDatasheetsWriteThem(String text, long expected) {
        assertThat(Measure.parseSize(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "MiB", "1.5 B", "12 furlongs", "-4 KiB", "0x40"})
    void badSizesAreRefused(String text) {
        assertThatThrownBy(() -> Measure.parseSize(text)).isInstanceOf(ValidationError.class);
    }

    @ParameterizedTest
    @CsvSource({
        "0, 0 B",
        "900, 900 B",
        "65536, 64 KiB",
        "16777216, 16 MiB",
        "1572864, 1.5 MiB",
        "3825788, 3.6 MiB",
    })
    void sizesFormatBackReadably(long size, String expected) {
        assertThat(Measure.formatSize(size)).isEqualTo(expected);
    }

    @Test
    void formattingANegativeSizeIsABugWorthRaising() {
        assertThatThrownBy(() -> Measure.formatSize(-1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void frequencyConstructorsAgree() {
        assertThat(Frequency.megahertz(30)).isEqualTo(new Frequency(30_000_000));
        assertThat(Frequency.kilohertz(1500)).isEqualTo(new Frequency(1_500_000));
        assertThat(Frequency.megahertz(1.5)).isEqualTo(new Frequency(1_500_000));
    }

    @ParameterizedTest
    @CsvSource({
        "30 MHz, 30000000",
        "30MHz, 30000000",
        "6.25 MHz, 6250000",
        "100 kHz, 100000",
        "400, 400",
    })
    void frequenciesParse(String text, long hertz) {
        assertThat(Frequency.parse(text).hertz()).isEqualTo(hertz);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "fast", "30 rpm", "-5 MHz"})
    void badFrequenciesAreRefused(String text) {
        assertThatThrownBy(() -> Frequency.parse(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void zeroIsNotAClock() {
        assertThatThrownBy(() -> new Frequency(0)).isInstanceOf(ValidationError.class);
    }

    @Test
    void periodIsWholePicoseconds() {
        assertThat(Frequency.megahertz(100).periodPs()).isEqualTo(10_000);
        assertThat(Frequency.megahertz(300).periodPs()).isEqualTo(3_333);
    }

    @Test
    void dividingMatchesWhatAnAdapterDoes() {
        assertThat(Frequency.megahertz(30).dividedBy(4)).isEqualTo(new Frequency(7_500_000));
        assertThatThrownBy(() -> Frequency.megahertz(30).dividedBy(0)).isInstanceOf(ValidationError.class);
    }

    @Test
    void atMostHoldsAChainToItsSlowestDevice() {
        Frequency fast = Frequency.megahertz(30);
        Frequency slow = Frequency.megahertz(6);
        assertThat(fast.atMost(slow)).isEqualTo(slow);
        assertThat(slow.atMost(fast)).isEqualTo(slow);
    }

    @Test
    void frequenciesOrder() {
        assertThat(Frequency.megahertz(6)).isLessThan(Frequency.megahertz(30));
        assertThat(Collections.max(List.of(Frequency.megahertz(6), Frequency.megahertz(30)))
                        .hertz())
                .isEqualTo(30_000_000);
    }

    @ParameterizedTest
    @CsvSource({
        "30000000, 30 MHz",
        "6250000, 6.250 MHz",
        "100000, 100 kHz",
        "1500, 1.500 kHz",
        "400, 400 Hz",
    })
    void frequenciesPrint(long hertz, String text) {
        assertThat(new Frequency(hertz)).hasToString(text);
    }

    @ParameterizedTest
    @CsvSource({"0.147, 147", "-0.023, -23", "12.500, 12500", "3, 3000", "-1.2, -1200"})
    void reportNanosecondsBecomePicoseconds(String text, long picoseconds) {
        assertThat(Measure.picosecondsFromNs(text)).isEqualTo(picoseconds);
    }

    @Test
    void floatsAreAcceptedButStillPinnedToPicoseconds() {
        assertThat(Measure.picosecondsFromNs(0.147)).isEqualTo(147);
        assertThat(Measure.picosecondsFromNs(-2.0)).isEqualTo(-2000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "0.1234", "nope", "1e-9"})
    void timingValuesWithTooMuchPrecisionAreRefused(String text) {
        assertThatThrownBy(() -> Measure.picosecondsFromNs(text)).isInstanceOf(ValidationError.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {147, -23, 12500, 0, -1200})
    void timingValuesRoundTrip(long picoseconds) {
        assertThat(Measure.picosecondsFromNs(Measure.renderNs(picoseconds))).isEqualTo(picoseconds);
    }

    @Test
    void renderingPadsTheFraction() {
        assertThat(Measure.renderNs(7)).isEqualTo("0.007");
        assertThat(Measure.renderNs(-7)).isEqualTo("-0.007");
    }
}
