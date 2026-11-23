package io.github.rkaushikdev.carrierworks.thermal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThermalSamplesTest {

    private static final Instant NOW = Instant.parse("2025-09-10T12:00:00Z");
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    /** A log for bx-14 whose readings are evenly spaced and end at {@link #NOW}. */
    private static ThermalLog logWith(Duration spacing, int... readings) {
        ThermalLog log = new ThermalLog();
        Instant moment = NOW.minus(spacing.multipliedBy(readings.length));
        for (int value : readings) {
            moment = moment.plus(spacing);
            log.add("bx-14", moment, value);
        }
        return log;
    }

    private static ThermalLog logWith(int... readings) {
        return logWith(Duration.ofSeconds(20), readings);
    }

    @Test
    void aSampleCarriesItsBoardAndReading() {
        ThermalSample sample = new ThermalSample("BX-0014", NOW, 62_500);
        assertThat(sample.board()).isEqualTo("bx-14");
        assertThat(sample.describe()).startsWith("bx-14 62.5C at ");
    }

    @Test
    void aReadingNoDeviceWouldProduceIsRefused() {
        assertThatThrownBy(() -> new ThermalSample("bx-14", NOW, 500_000)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new ThermalSample("bx-14", NOW, -70_000)).isInstanceOf(ValidationError.class);
    }

    /**
     * A sample time with no zone is refused. A sample keeps its moment as an {@link Instant}, which
     * is always UTC, so a zoneless time cannot be handed in; the test holds the type to that.
     */
    @Test
    void sampleTimesAreInUtc() {
        assertThat(Arrays.stream(ThermalSample.class.getRecordComponents())
                        .filter(component -> component.getName().equals("at"))
                        .<Object>map(component -> component.getType()))
                .containsExactly(Instant.class);
    }

    @Test
    void theLatestReadingComesBack() {
        ThermalLog log = logWith(60_000, 61_000, 62_000);
        assertThat(log.latest("bx-14")).map(ThermalSample::millidegrees).contains(62_000);
        assertThat(log.latest("bx-15")).isEmpty();
    }

    @Test
    void aWindowOnlyHoldsWhatIsInsideIt() {
        ThermalLog log = logWith(MINUTE, 60_000, 61_000, 62_000);
        assertThat(log.window("bx-14", NOW, Duration.ofSeconds(90))).hasSize(2);
        assertThat(log.window("bx-14", NOW, Duration.ofHours(1))).hasSize(3);
    }

    @Test
    void aWindowOfNoLengthIsABug() {
        assertThatThrownBy(() -> new ThermalLog().window("bx-14", NOW, Duration.ZERO))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void theAverageSmoothsTheNoiseOut() {
        ThermalLog log = logWith(60_000, 62_000, 61_000, 63_000);
        assertThat(log.average("bx-14", NOW, FIVE_MINUTES)).hasValue(61_500);
    }

    /** Die readings off the system monitor and inlet readings off the rack, interleaved. */
    private static ThermalLog dieAndInlet(int[] die, int[] inlet) {
        ThermalLog log = new ThermalLog();
        Duration spacing = Duration.ofSeconds(20);
        for (int index = 0; index < die.length; index++) {
            Instant moment = NOW.minus(spacing.multipliedBy(die.length - index));
            log.add("bx-14", moment, die[index]);
            log.add("bx-14", moment.plusSeconds(5), inlet[index], "rack-inlet");
        }
        return log;
    }

    @Test
    void eachSensorIsAveragedOnItsOwn() {
        ThermalLog log = dieAndInlet(new int[] {88_000, 90_000}, new int[] {30_000, 31_000});
        assertThat(log.averages("bx-14", NOW, FIVE_MINUTES))
                .containsExactly(Map.entry("rack-inlet", 30_500), Map.entry("sysmon", 89_000));
    }

    @Test
    void theBoardIsJudgedOnItsHottestSensorNotOnAMixOfThem() {
        ThermalLog log = dieAndInlet(new int[] {88_000, 90_000}, new int[] {30_000, 31_000});
        assertThat(log.average("bx-14", NOW, FIVE_MINUTES)).hasValue(89_000);
    }

    @Test
    void aTrendIsReadOffOneSensorOnly() {
        ThermalLog log =
                dieAndInlet(new int[] {70_000, 72_000, 75_000, 78_000}, new int[] {30_000, 30_000, 29_000, 29_000});
        assertThat(log.rising("bx-14", NOW, FIVE_MINUTES)).contains(true);
        assertThat(log.hottestSensor("bx-14", NOW, FIVE_MINUTES))
                .extracting(ThermalSample::source)
                .containsOnly("sysmon");
    }

    @Test
    void twoReadingsFromEachOfTwoSensorsAreNotEnoughForATrend() {
        ThermalLog log = dieAndInlet(new int[] {70_000, 78_000}, new int[] {30_000, 29_000});
        assertThat(log.window("bx-14", NOW, FIVE_MINUTES)).hasSize(4);
        assertThat(log.rising("bx-14", NOW, FIVE_MINUTES)).isEmpty();
    }

    @Test
    void thePeakIsTheWorstReadingInTheWindow() {
        ThermalLog log = logWith(60_000, 72_000, 61_000);
        assertThat(log.peak("bx-14", NOW, FIVE_MINUTES)).hasValue(72_000);
    }

    @Test
    void aBoardWithNoSamplesAveragesToNothing() {
        ThermalLog log = new ThermalLog();
        assertThat(log.average("bx-14", NOW, FIVE_MINUTES)).isEmpty();
        assertThat(log.peak("bx-14", NOW, FIVE_MINUTES)).isEmpty();
    }

    @Test
    void aBoardNobodyHasHeardFromIsStale() {
        ThermalLog log = new ThermalLog();
        assertThat(log.stale("bx-14", NOW, Duration.ofMinutes(3))).isTrue();
    }

    @Test
    void aBoardReportingRegularlyIsNotStale() {
        ThermalLog log = logWith(60_000, 61_000);
        assertThat(log.stale("bx-14", NOW, Duration.ofMinutes(3))).isFalse();
    }

    @Test
    void aBoardThatStoppedReportingGoesStale() {
        ThermalLog log = logWith(60_000, 61_000);
        assertThat(log.stale("bx-14", NOW.plus(Duration.ofMinutes(10)), Duration.ofMinutes(3)))
                .isTrue();
    }

    @Test
    void aRisingBoardIsSeenRising() {
        ThermalLog log = logWith(60_000, 61_000, 64_000, 66_000);
        assertThat(log.rising("bx-14", NOW, FIVE_MINUTES)).contains(true);
    }

    @Test
    void aCoolingBoardIsSeenCooling() {
        ThermalLog log = logWith(70_000, 68_000, 64_000, 61_000);
        assertThat(log.rising("bx-14", NOW, FIVE_MINUTES)).contains(false);
    }

    @Test
    void aTrendNeedsEnoughSamplesToMeanAnything() {
        ThermalLog log = logWith(60_000, 70_000);
        assertThat(log.rising("bx-14", NOW, FIVE_MINUTES)).isEmpty();
    }

    @Test
    void boardsAreKeptApart() {
        ThermalLog log = new ThermalLog();
        log.add("bx-14", NOW, 60_000);
        log.add("bx-15", NOW, 80_000);
        assertThat(log.boards()).containsExactly("bx-14", "bx-15");
        assertThat(log.latest("bx-15").orElseThrow().millidegrees()).isEqualTo(80_000);
        assertThat(log.size()).isEqualTo(2);
    }

    @Test
    void theLogForgetsTheOldestSamplesOnceItIsFull() {
        ThermalLog log = new ThermalLog(3);
        for (int index = 0; index < 10; index++) {
            log.add("bx-14", NOW.plusSeconds(index), 60_000 + index);
        }
        assertThat(log.size()).isEqualTo(3);
        assertThat(log.latest("bx-14").orElseThrow().millidegrees()).isEqualTo(60_009);
    }

    @Test
    void aLogThatKeepsNothingIsRefused() {
        assertThatThrownBy(() -> new ThermalLog(1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void pruningDropsTheOldOnesAndSaysHowMany() {
        ThermalLog log = logWith(MINUTE, 60_000, 61_000, 62_000);
        int dropped = log.prune(NOW.minusSeconds(30));
        assertThat(dropped).isEqualTo(2);
        assertThat(log.size()).isEqualTo(1);
    }

    @Test
    void pruningEverythingTakesTheBoardOffTheList() {
        ThermalLog log = logWith(60_000, 61_000);
        log.prune(NOW.plus(Duration.ofHours(1)));
        assertThat(log.boards()).isEmpty();
        assertThat(log.size()).isZero();
    }

    @Test
    void theAgeOfASampleNeverGoesNegative() {
        ThermalSample sample = new ThermalSample("bx-14", NOW, 60_000);
        assertThat(sample.age(NOW.minus(FIVE_MINUTES))).isEqualTo(Duration.ZERO);
        assertThat(sample.age(NOW.plus(FIVE_MINUTES))).isEqualTo(FIVE_MINUTES);
    }
}
