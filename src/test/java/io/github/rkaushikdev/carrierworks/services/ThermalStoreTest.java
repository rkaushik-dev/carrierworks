package io.github.rkaushikdev.carrierworks.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.AuditEntry;
import io.github.rkaushikdev.carrierworks.BenchSettings;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ThermalLimits;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.thermal.AlarmBook;
import io.github.rkaushikdev.carrierworks.thermal.SupervisorAction;
import io.github.rkaushikdev.carrierworks.thermal.ThermalLog;
import io.github.rkaushikdev.carrierworks.thermal.ThermalSample;
import io.github.rkaushikdev.carrierworks.thermal.ThermalStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThermalStoreTest {

    private static final Instant WHEN = Instant.parse("2026-03-10T14:00:00Z");
    private static final ThermalLimits LIMITS = new ThermalLimits(76_000, 86_000, 90_000);
    private static final Duration EVERY = Duration.ofSeconds(20);

    private BenchDatabase database;

    @BeforeEach
    void open() {
        database = BenchDatabase.inMemory();
        database.createSchema();
    }

    @AfterEach
    void close() {
        database.close();
    }

    /** Run a case in one unit of work, on a bench holding bx-14 and bx-15. */
    private void onBench(Consumer<UnitOfWork> body) {
        database.run(unit -> {
            Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), WHEN.minus(Duration.ofDays(1)));
            Inventory.addBoard(unit, BoardSetup.artix("bx-15").build(), WHEN.minus(Duration.ofDays(1)));
            body.accept(unit);
        });
    }

    /** Record a run of readings ending at {@link #WHEN}, one every twenty seconds. */
    private static void stream(UnitOfWork unit, String board, int... readings) {
        Instant first = WHEN.minus(EVERY.multipliedBy(readings.length - 1));
        for (int index = 0; index < readings.length; index++) {
            Thermal.record(unit, board, first.plus(EVERY.multipliedBy(index)), readings[index]);
        }
    }

    private static List<SupervisorAction.Kind> kinds(List<SupervisorAction> actions) {
        return actions.stream().map(SupervisorAction::action).toList();
    }

    @Test
    void aReadingIsStoredAndReadBack() {
        onBench(unit -> {
            Thermal.record(unit, "bx-14", WHEN, 64_500);
            ThermalSample latest = unit.thermal().latest("bx-14").orElseThrow();
            assertThat(latest.millidegrees()).isEqualTo(64_500);
            assertThat(latest.source()).isEqualTo("sysmon");
        });
    }

    @Test
    void aReadingForABoardNobodyHasIsRefused() {
        onBench(unit -> assertThatThrownBy(() -> Thermal.record(unit, "bx-99", WHEN, 64_500))
                .isInstanceOf(NotFound.class));
    }

    @Test
    void aReadingThatIsNotATemperatureIsRefused() {
        onBench(unit -> assertThatThrownBy(() -> Thermal.record(unit, "bx-14", WHEN, 400_000))
                .isInstanceOf(ValidationError.class));
    }

    @Test
    void theLatestReadingIsTheNewestOne() {
        onBench(unit -> {
            stream(unit, "bx-14", 60_000, 62_000, 66_000);
            assertThat(unit.thermal().latest("bx-14"))
                    .map(ThermalSample::millidegrees)
                    .contains(66_000);
        });
    }

    @Test
    void aBoardWithNoReadingsHasNoLatest() {
        onBench(unit -> assertThat(unit.thermal().latest("bx-15")).isEmpty());
    }

    @Test
    void readingsComeBackInTimeOrder() {
        onBench(unit -> {
            stream(unit, "bx-14", 60_000, 62_000, 66_000);
            assertThat(unit.thermal().since(WHEN.minus(Duration.ofHours(1)), "bx-14"))
                    .extracting(ThermalSample::millidegrees)
                    .containsExactly(60_000, 62_000, 66_000);
        });
    }

    @Test
    void aWindowLeavesOutWhatIsOlderThanIt() {
        onBench(unit -> {
            Thermal.record(unit, "bx-14", WHEN.minus(Duration.ofHours(2)), 55_000);
            stream(unit, "bx-14", 60_000, 62_000);
            ThermalLog log = Thermal.loadWindow(unit, WHEN, Duration.ofMinutes(15));
            assertThat(log.since("bx-14", WHEN.minus(Duration.ofMinutes(15)))).hasSize(2);
        });
    }

    @Test
    void oneQueryLoadsEveryBoard() {
        onBench(unit -> {
            stream(unit, "bx-14", 60_000, 61_000);
            stream(unit, "bx-15", 70_000, 71_000);
            ThermalLog log = Thermal.loadWindow(unit, WHEN, Duration.ofMinutes(15));
            assertThat(log.boards()).containsExactlyInAnyOrder("bx-14", "bx-15");
        });
    }

    @Test
    void aSourceOtherThanTheDieSensorIsKept() {
        onBench(unit -> {
            Thermal.record(unit, "bx-14", WHEN, 40_000, "inlet");
            assertThat(unit.thermal().latest("bx-14"))
                    .map(ThermalSample::source)
                    .contains("inlet");
        });
    }

    @Test
    void countingIsPerBoard() {
        onBench(unit -> {
            stream(unit, "bx-14", 60_000, 61_000, 62_000);
            stream(unit, "bx-15", 70_000);
            assertThat(unit.thermal().count("bx-14")).isEqualTo(3);
            assertThat(unit.thermal().count()).isEqualTo(4);
        });
    }

    @Test
    void pruningDropsTheOldReadingsAndSaysHowMany() {
        onBench(unit -> {
            Thermal.record(unit, "bx-14", WHEN.minus(Duration.ofDays(9)), 55_000);
            Thermal.record(unit, "bx-14", WHEN.minus(Duration.ofDays(8)), 56_000);
            stream(unit, "bx-14", 60_000);
            assertThat(Thermal.prune(unit, WHEN)).isEqualTo(2);
            assertThat(unit.thermal().count("bx-14")).isEqualTo(1);
        });
    }

    @Test
    void pruningKeepsWhatIsInsideTheWindow() {
        onBench(unit -> {
            stream(unit, "bx-14", 60_000, 61_000);
            assertThat(Thermal.prune(unit, WHEN)).isZero();
        });
    }

    @Test
    void aQuietBenchProducesNoActions() {
        onBench(unit -> {
            stream(unit, "bx-14", 60_000, 61_000, 62_000);
            stream(unit, "bx-15", 55_000, 55_500, 56_000);
            assertThat(Thermal.tick(unit, WHEN, new AlarmBook(LIMITS))).isEmpty();
        });
    }

    @Test
    void aWarmBoardIsNotedRatherThanTouched() {
        onBench(unit -> {
            stream(unit, "bx-14", 77_000, 78_000, 79_000);
            List<SupervisorAction> actions = Thermal.tick(unit, WHEN, new AlarmBook(LIMITS));
            assertThat(kinds(actions)).containsExactly(SupervisorAction.Kind.NOTE);
            assertThat(unit.boards().state("bx-14").state()).isEqualTo(BoardStatus.AVAILABLE);
        });
    }

    @Test
    void aCriticalBoardWarnsAndNamesWhatIsDownwind() {
        onBench(unit -> {
            stream(unit, "bx-14", 87_000, 87_500, 88_000);
            List<SupervisorAction> actions = Thermal.tick(unit, WHEN, new AlarmBook(LIMITS));
            assertThat(actions.getFirst().action()).isEqualTo(SupervisorAction.Kind.WARN);
        });
    }

    @Test
    void aBoardOverTheShutdownLineIsFaulted() {
        onBench(unit -> {
            stream(unit, "bx-14", 91_000, 91_500, 92_000);
            List<SupervisorAction> actions = Thermal.tick(unit, WHEN, new AlarmBook(LIMITS));
            assertThat(actions.getFirst().action()).isEqualTo(SupervisorAction.Kind.SHUTDOWN);
            assertThat(unit.boards().state("bx-14").state()).isEqualTo(BoardStatus.FAULTED);
        });
    }

    @Test
    void aShutdownGivesTheLeaseBack() {
        onBench(unit -> {
            Leasing.acquire(unit, "bx-14", "team-a", "capture", WHEN.minus(Duration.ofMinutes(30)));
            stream(unit, "bx-14", 91_000, 91_500, 92_000);
            Thermal.tick(unit, WHEN, new AlarmBook(LIMITS));
            assertThat(unit.leases().forBoard("bx-14", WHEN)).isEmpty();
        });
    }

    @Test
    void aShutdownLeavesALineInTheAuditTrail() {
        onBench(unit -> {
            stream(unit, "bx-14", 91_000, 92_000);
            Thermal.tick(unit, WHEN, new AlarmBook(LIMITS));
            List<AuditEntry> trail = unit.audit().forSubject("bx-14");
            assertThat(trail).extracting(AuditEntry::action).contains("shutdown");
            assertThat(trail.stream()
                            .filter(entry -> entry.actor().equals("thermal"))
                            .map(AuditEntry::action)
                            .collect(Collectors.toSet()))
                    .containsExactlyInAnyOrder("shutdown", "board-state");
        });
    }

    @Test
    void aNoteIsNotWorthAnAuditLine() {
        onBench(unit -> {
            stream(unit, "bx-14", 77_000, 78_000);
            Thermal.tick(unit, WHEN, new AlarmBook(LIMITS));
            assertThat(unit.audit().forSubject("bx-14"))
                    .filteredOn(entry -> entry.actor().equals("thermal"))
                    .isEmpty();
        });
    }

    @Test
    void theStatusPassChangesNothing() {
        onBench(unit -> {
            stream(unit, "bx-14", 91_000, 92_000);
            List<ThermalStatus> statuses = Thermal.statusOf(unit, WHEN, BenchSettings.defaults());
            assertThat(statuses.getFirst().board()).isIn("bx-14", "bx-15");
            assertThat(unit.boards().state("bx-14").state()).isEqualTo(BoardStatus.AVAILABLE);
        });
    }

    @Test
    void hysteresisNeedsTheSameBookBetweenTicks() {
        onBench(unit -> {
            AlarmBook book = new AlarmBook(LIMITS);
            stream(unit, "bx-14", 87_000, 88_000);
            Thermal.tick(unit, WHEN, book);
            Thermal.record(unit, "bx-14", WHEN.plus(EVERY), 84_000);
            List<SupervisorAction> again = Thermal.tick(unit, WHEN.plus(EVERY), book);
            assertThat(kinds(again)).containsExactly(SupervisorAction.Kind.WARN);
        });
    }
}
