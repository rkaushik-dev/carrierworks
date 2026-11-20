package io.github.rkaushikdev.carrierworks.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import io.github.rkaushikdev.carrierworks.thermal.ThermalLog;
import io.github.rkaushikdev.carrierworks.thermal.ThermalSample;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThermalBatchTest {

    private static final Instant WHEN = Instant.parse("2026-06-12T07:00:00Z");
    private static final int BASE = 60_000;

    private BenchDatabase database;

    @BeforeEach
    void open() {
        database = BenchDatabase.inMemory();
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

    /** What one controller posts after reading its boards, half a degree apart. */
    private static List<Thermal.Reading> poll(Instant at, String source, String... boards) {
        List<Thermal.Reading> readings = new ArrayList<>();
        for (int index = 0; index < boards.length; index++) {
            readings.add(new Thermal.Reading(boards[index], at, BASE + index * 500, source));
        }
        return readings;
    }

    private static List<Thermal.Reading> poll(String... boards) {
        return poll(WHEN, ThermalSample.DEFAULT_SOURCE, boards);
    }

    @Test
    void aBatchIsStored() {
        onBench(unit -> {
            List<ThermalSample> stored = Thermal.recordBatch(unit, poll("bx-14", "bx-15"));
            assertThat(stored).extracting(ThermalSample::board).containsExactly("bx-14", "bx-15");
            assertThat(unit.thermal().count()).isEqualTo(2);
        });
    }

    @Test
    void theReadingsComeBackWithTheirValues() {
        onBench(unit -> {
            Thermal.recordBatch(unit, poll("bx-14", "bx-15"));
            assertThat(unit.thermal().latest("bx-15"))
                    .map(ThermalSample::millidegrees)
                    .contains(60_500);
        });
    }

    @Test
    void theSourceIsKept() {
        onBench(unit -> {
            Thermal.recordBatch(unit, poll(WHEN, "inlet", "bx-14"));
            assertThat(unit.thermal().latest("bx-14"))
                    .map(ThermalSample::source)
                    .contains("inlet");
        });
    }

    @Test
    void anEmptyBatchStoresNothing() {
        onBench(unit -> {
            assertThat(Thermal.recordBatch(unit, List.of())).isEmpty();
            assertThat(unit.thermal().count()).isZero();
        });
    }

    @Test
    void aBatchNamingABoardNobodyHasIsRefused() {
        onBench(unit -> assertThatThrownBy(() -> Thermal.recordBatch(unit, poll("bx-14", "bx-99")))
                .isInstanceOf(NotFound.class));
    }

    @Test
    void aRefusedBatchStoresNoneOfIt() {
        onBench(unit -> {
            assertThatThrownBy(() -> Thermal.recordBatch(unit, poll("bx-14", "bx-99")))
                    .isInstanceOf(NotFound.class);
            assertThat(unit.thermal().count()).isZero();
        });
    }

    @Test
    void aReadingOffTheScaleIsRefusedBeforeAnythingIsWritten() {
        onBench(unit -> {
            assertThatThrownBy(() ->
                            Thermal.recordBatch(unit, List.of(new Thermal.Reading("bx-14", WHEN, 900_000, "sysmon"))))
                    .isInstanceOf(ValidationError.class);
            assertThat(unit.thermal().count()).isZero();
        });
    }

    @Test
    void aBatchAndSingleRecordsAgree() {
        onBench(unit -> {
            Thermal.recordBatch(unit, List.of(new Thermal.Reading("bx-14", WHEN, 64_500, "sysmon")));
            Thermal.record(unit, "bx-15", WHEN, 64_500);
            assertThat(unit.thermal().latest("bx-14")).contains(new ThermalSample("bx-14", WHEN, 64_500));
            assertThat(unit.thermal().latest("bx-15")).contains(new ThermalSample("bx-15", WHEN, 64_500));
        });
    }

    @Test
    void aWholeRackPollLandsInOneCall() {
        onBench(unit -> {
            List<Thermal.Reading> readings = new ArrayList<>();
            for (int minute = 0; minute < 20; minute++) {
                Instant moment = WHEN.minus(Duration.ofSeconds(20L * minute));
                readings.addAll(poll(moment, ThermalSample.DEFAULT_SOURCE, "bx-14", "bx-15"));
            }
            assertThat(Thermal.recordBatch(unit, readings)).hasSize(40);
            assertThat(unit.thermal().count("bx-14")).isEqualTo(20);
        });
    }

    @Test
    void theRepositoryCountsWhatItWrote() {
        onBench(unit -> {
            List<ThermalSample> samples = List.of(new ThermalSample("bx-14", WHEN, 61_000));
            assertThat(unit.thermal().recordMany(samples)).isEqualTo(1);
            assertThat(unit.thermal().recordMany(List.of())).isZero();
        });
    }

    @Test
    void theWindowReadsABatchBack() {
        onBench(unit -> {
            Thermal.recordBatch(unit, poll("bx-14", "bx-15"));
            ThermalLog log = Thermal.loadWindow(unit, WHEN, Duration.ofMinutes(15));
            assertThat(log.boards()).containsExactlyInAnyOrder("bx-14", "bx-15");
        });
    }
}
