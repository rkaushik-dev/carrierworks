package io.github.rkaushikdev.carrierworks.thermal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ThermalLimits;
import io.github.rkaushikdev.carrierworks.ThermalState;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlarmsTest {

    private static final Instant NOW = Instant.parse("2025-09-18T15:00:00Z");

    private static ThermalLimits limits() {
        return new ThermalLimits(76_000, 86_000, 90_000);
    }

    private static AlarmBook book() {
        return new AlarmBook(limits());
    }

    /** A log for one board whose readings are twenty seconds apart, the last taken at {@link #NOW}. */
    private static ThermalLog logAt(String board, int... readings) {
        ThermalLog log = new ThermalLog();
        Duration spacing = Duration.ofSeconds(20);
        Instant moment = NOW.minus(spacing.multipliedBy(readings.length - 1L));
        for (int value : readings) {
            log.add(board, moment, value);
            moment = moment.plus(spacing);
        }
        return log;
    }

    private static List<String> boards(List<ThermalStatus> statuses) {
        return statuses.stream().map(ThermalStatus::board).toList();
    }

    @Test
    void aCoolBoardIsNormal() {
        ThermalStatus status = book().update("bx-14", logAt("bx-14", 60_000, 61_000), NOW);
        assertThat(status.state()).isEqualTo(ThermalState.NORMAL);
        assertThat(status.healthy()).isTrue();
        assertThat(status.alarming()).isFalse();
        assertThat(status.millidegrees()).hasValue(60_500);
    }

    @Test
    void aHotBoardWarns() {
        ThermalStatus status = book().update("bx-14", logAt("bx-14", 77_000, 78_000), NOW);
        assertThat(status.state()).isEqualTo(ThermalState.WARN);
        assertThat(status.alarming()).isFalse();
    }

    @Test
    void aVeryHotBoardIsCriticalAndAlarming() {
        ThermalStatus status = book().update("bx-14", logAt("bx-14", 87_000, 88_000), NOW);
        assertThat(status.state()).isEqualTo(ThermalState.CRITICAL);
        assertThat(status.alarming()).isTrue();
    }

    @Test
    void theAverageDecidesRatherThanOneSpike() {
        ThermalStatus status = book().update("bx-14", logAt("bx-14", 60_000, 90_000, 60_000, 61_000), NOW);
        assertThat(status.state()).isEqualTo(ThermalState.NORMAL);
    }

    @Test
    void hysteresisHoldsABoardInItsStateOnTheWayDown() {
        AlarmBook live = book();
        ThermalLog log = new ThermalLog();
        log.add("bx-14", NOW.minusSeconds(20), 88_000);
        log.add("bx-14", NOW, 88_000);
        assertThat(live.update("bx-14", log, NOW).state()).isEqualTo(ThermalState.CRITICAL);
        Instant later = NOW.plusSeconds(40);
        log.add("bx-14", later, 84_000);
        assertThat(live.update("bx-14", log, later).state()).isEqualTo(ThermalState.CRITICAL);
    }

    @Test
    void aBoardThatCoolsProperlyComesBackDown() {
        AlarmBook live = book();
        ThermalLog log = new ThermalLog();
        log.add("bx-14", NOW, 88_000);
        live.update("bx-14", log, NOW);
        Instant later = NOW.plus(Duration.ofMinutes(2));
        log.add("bx-14", later, 60_000);
        assertThat(live.update("bx-14", log, later).state()).isEqualTo(ThermalState.NORMAL);
    }

    @Test
    void theSinceTimeOnlyMovesWhenTheStateDoes() {
        AlarmBook live = book();
        ThermalLog log = new ThermalLog();
        log.add("bx-14", NOW, 60_000);
        ThermalStatus first = live.update("bx-14", log, NOW);
        Instant later = NOW.plusSeconds(40);
        log.add("bx-14", later, 61_000);
        ThermalStatus second = live.update("bx-14", log, later);
        assertThat(second.since()).isEqualTo(first.since());
        assertThat(second.heldFor(later)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void aBoardThatStopsReportingBecomesUnknown() {
        AlarmBook live = book();
        ThermalLog log = logAt("bx-14", 60_000, 61_000);
        live.update("bx-14", log, NOW);
        Instant quiet = NOW.plus(Duration.ofMinutes(10));
        ThermalStatus status = live.update("bx-14", log, quiet);
        assertThat(status.state()).isEqualTo(ThermalState.UNKNOWN);
        assertThat(status.stale()).isTrue();
        assertThat(status.alarming()).isTrue();
        assertThat(status.describe()).isEqualTo("bx-14 not reporting");
    }

    @Test
    void aBoardThatNeverReportedIsUnknownToo() {
        ThermalStatus status = book().update("bx-14", new ThermalLog(), NOW);
        assertThat(status.state()).isEqualTo(ThermalState.UNKNOWN);
        assertThat(status.millidegrees()).isEmpty();
    }

    @Test
    void aBoardThatStartsReportingAgainLeavesUnknown() {
        AlarmBook live = book();
        ThermalLog log = new ThermalLog();
        live.update("bx-14", log, NOW);
        Instant later = NOW.plus(Duration.ofMinutes(1));
        log.add("bx-14", later, 62_000);
        assertThat(live.update("bx-14", log, later).state()).isEqualTo(ThermalState.NORMAL);
    }

    @Test
    void unknownSortsWithCriticalRatherThanWithNormal() {
        AlarmBook live = book();
        live.update("bx-14", logAt("bx-14", 60_000), NOW);
        live.update("bx-15", new ThermalLog(), NOW);
        List<String> worst = boards(live.worstFirst());
        assertThat(worst.getFirst()).isEqualTo("bx-15");
        assertThat(boards(live.alarming())).containsExactly("bx-15");
    }

    @Test
    void everyBoardInTheLogGetsUpdated() {
        ThermalLog log = new ThermalLog();
        log.add("bx-14", NOW, 60_000);
        log.add("bx-15", NOW, 88_000);
        AlarmBook live = book();
        List<ThermalStatus> statuses = live.updateAll(log, NOW);
        assertThat(boards(statuses)).containsExactly("bx-15", "bx-14");
        assertThat(live.size()).isEqualTo(2);
        assertThat(live.boards()).containsExactly("bx-14", "bx-15");
    }

    @Test
    void boardsCanBeAskedForByState() {
        ThermalLog log = new ThermalLog();
        log.add("bx-14", NOW, 60_000);
        log.add("bx-15", NOW, 88_000);
        AlarmBook live = book();
        live.updateAll(log, NOW);
        assertThat(boards(live.inState(ThermalState.CRITICAL))).containsExactly("bx-15");
        assertThat(boards(live.inState(ThermalState.NORMAL))).containsExactly("bx-14");
    }

    @Test
    void forgettingABoardStopsTheBenchWatchingIt() {
        AlarmBook live = book();
        live.update("bx-14", logAt("bx-14", 60_000), NOW);
        live.forget("bx-14");
        assertThat(live.status("bx-14")).isEmpty();
        assertThat(live.size()).isZero();
    }

    @Test
    void aStatusForAStateNobodyHasIsRefused() {
        assertThatThrownBy(() -> new ThermalStatus("bx-14", ThermalState.parse("toasty"), NOW))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void anAlarmBookWithNoWindowIsRefused() {
        assertThatThrownBy(() -> new AlarmBook(limits(), Duration.ZERO, AlarmBook.DEFAULT_STALE_AFTER))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new AlarmBook(limits(), AlarmBook.DEFAULT_WINDOW, Duration.ZERO))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aStatusDescribesItsReading() {
        ThermalStatus status = book().update("bx-14", logAt("bx-14", 62_500), NOW);
        assertThat(status.describe()).isEqualTo("bx-14 normal at 62.5C");
    }
}
