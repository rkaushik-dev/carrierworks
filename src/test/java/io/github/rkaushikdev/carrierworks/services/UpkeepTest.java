package io.github.rkaushikdev.carrierworks.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardSetup;
import io.github.rkaushikdev.carrierworks.inventory.BoardSlot;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.inventory.MaintenanceKind;
import io.github.rkaushikdev.carrierworks.inventory.MaintenanceRecord;
import io.github.rkaushikdev.carrierworks.persistence.BenchDatabase;
import io.github.rkaushikdev.carrierworks.persistence.UnitOfWork;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UpkeepTest {

    private static final Instant WHEN = Instant.parse("2026-04-08T09:00:00Z");
    private static final Duration YEAR = Duration.ofDays(365);

    private BenchDatabase database;

    @BeforeEach
    void open() {
        database = BenchDatabase.inMemory();
    }

    @AfterEach
    void close() {
        database.close();
    }

    /** A board with nothing on it that measures anything. */
    private static Board plain(String asset) {
        return BoardSetup.artix(asset)
                .slots(new BoardSlot("pmod0", "pmod", "pmod-rs485"), new BoardSlot("fmc0", "fmc-lpc"))
                .build();
    }

    /** Run a case in one unit of work, on a bench holding a converter board and a plain one. */
    private void onBench(Consumer<UnitOfWork> body) {
        database.run(unit -> {
            Inventory.addBoard(unit, BoardSetup.kintex("bx-14").build(), WHEN.minus(Duration.ofDays(400)));
            Inventory.addBoard(unit, plain("bx-15"), WHEN.minus(Duration.ofDays(400)));
            body.accept(unit);
        });
    }

    private static MaintenanceRecord visit(UnitOfWork unit, String board, String kind, Instant when, String notes) {
        return Upkeep.logVisit(unit, board, kind, when, "sam", notes, 0);
    }

    private static MaintenanceRecord visit(UnitOfWork unit, String kind, Instant when) {
        return visit(unit, "bx-14", kind, when, "");
    }

    @Test
    void aVisitIsWrittenDown() {
        onBench(unit -> {
            MaintenanceRecord record = visit(unit, "bx-14", "repair", WHEN, "reseated the fmc");
            assertThat(record.board()).isEqualTo("bx-14");
            assertThat(unit.maintenance().forBoard("bx-14").getFirst().notes()).isEqualTo("reseated the fmc");
        });
    }

    @Test
    void aKindTheBenchDoesNotKnowIsRefused() {
        onBench(unit ->
                assertThatThrownBy(() -> visit(unit, "percussive", WHEN)).isInstanceOf(ValidationError.class));
    }

    @Test
    void aVisitToABoardNobodyHasIsRefused() {
        onBench(unit -> assertThatThrownBy(() -> visit(unit, "bx-99", "repair", WHEN, ""))
                .isInstanceOf(NotFound.class));
    }

    @Test
    void visitsComeBackOldestFirst() {
        onBench(unit -> {
            visit(unit, "bx-14", "repair", WHEN.minus(Duration.ofDays(2)), "first");
            visit(unit, "bx-14", "repair", WHEN, "second");
            assertThat(unit.maintenance().forBoard("bx-14"))
                    .extracting(MaintenanceRecord::notes)
                    .containsExactly("first", "second");
        });
    }

    @Test
    void theLastVisitOfAKindIsTheNewest() {
        onBench(unit -> {
            visit(unit, "calibration", WHEN.minus(Duration.ofDays(200)));
            visit(unit, "repair", WHEN);
            assertThat(unit.maintenance().last("bx-14", MaintenanceKind.CALIBRATION))
                    .map(MaintenanceRecord::at)
                    .contains(WHEN.minus(Duration.ofDays(200)));
        });
    }

    @Test
    void aBoardWithNoVisitsHasNoLast() {
        onBench(unit -> assertThat(unit.maintenance().last("bx-15")).isEmpty());
    }

    @Test
    void theWholeLogLoadsAsABook() {
        onBench(unit -> {
            visit(unit, "bx-14", "repair", WHEN, "");
            visit(unit, "bx-15", "repair", WHEN, "");
            assertThat(unit.maintenance().book().boards()).containsExactlyInAnyOrder("bx-14", "bx-15");
        });
    }

    @Test
    void aBoardWithAConverterNeedsCalibration() {
        onBench(unit -> {
            CalibrationState state = Upkeep.calibration(unit, "bx-14", WHEN);
            assertThat(state.required()).isTrue();
            assertThat(state.never()).isTrue();
            assertThat(state.valid()).isFalse();
        });
    }

    @Test
    void aBoardWithNoMeasuringCardNeedsNone() {
        onBench(unit -> {
            CalibrationState state = Upkeep.calibration(unit, "bx-15", WHEN);
            assertThat(state.required()).isFalse();
            assertThat(state.valid()).isTrue();
        });
    }

    @Test
    void aFreshCalibrationHolds() {
        onBench(unit -> {
            visit(unit, "calibration", WHEN.minus(Duration.ofDays(30)));
            CalibrationState state = Upkeep.calibration(unit, "bx-14", WHEN);
            assertThat(state.valid()).isTrue();
            assertThat(state.dueAt()).contains(WHEN.minus(Duration.ofDays(30)).plus(YEAR));
        });
    }

    @Test
    void aCalibrationOverAYearOldHasRunOut() {
        onBench(unit -> {
            visit(unit, "calibration", WHEN.minus(Duration.ofDays(400)));
            assertThat(Upkeep.calibration(unit, "bx-14", WHEN).valid()).isFalse();
        });
    }

    @Test
    void theNewestCalibrationIsTheOneThatCounts() {
        onBench(unit -> {
            visit(unit, "calibration", WHEN.minus(Duration.ofDays(400)));
            visit(unit, "calibration", WHEN.minus(Duration.ofDays(10)));
            assertThat(Upkeep.calibration(unit, "bx-14", WHEN).valid()).isTrue();
        });
    }

    @Test
    void aRepairDoesNotCountAsACalibration() {
        onBench(unit -> {
            visit(unit, "repair", WHEN.minus(Duration.ofDays(1)));
            assertThat(Upkeep.calibration(unit, "bx-14", WHEN).valid()).isFalse();
        });
    }

    @Test
    void theOverdueListNamesTheBoardThatNeverWas() {
        onBench(unit -> assertThat(Upkeep.overdue(unit, WHEN))
                .extracting(CalibrationState::board)
                .containsExactly("bx-14"));
    }

    @Test
    void aCalibratedBoardLeavesTheOverdueList() {
        onBench(unit -> {
            visit(unit, "calibration", WHEN.minus(Duration.ofDays(5)));
            assertThat(Upkeep.overdue(unit, WHEN)).isEmpty();
        });
    }

    @Test
    void theStateReadsAsOneLine() {
        onBench(unit -> {
            visit(unit, "calibration", WHEN.minus(Duration.ofDays(5)));
            assertThat(Upkeep.calibration(unit, "bx-14", WHEN).describe()).contains("calibrated 2026-04-03");
        });
    }

    @Test
    void aBoardNeedingNothingSaysSo() {
        onBench(unit ->
                assertThat(Upkeep.calibration(unit, "bx-15", WHEN).describe()).endsWith("needs no calibration"));
    }

    @Test
    void returningToServiceLogsAndMovesInOneGo() {
        onBench(unit -> {
            Inventory.moveState(unit, "bx-14", BoardStatus.MAINTENANCE, WHEN, "fan", "sam");
            Upkeep.returnToService(unit, "bx-14", WHEN, "sam", Upkeep.REPAIR, "new fan fitted", 0);
            assertThat(unit.boards().state("bx-14").state()).isEqualTo(BoardStatus.AVAILABLE);
            assertThat(unit.maintenance().forBoard("bx-14").getLast().notes()).isEqualTo("new fan fitted");
        });
    }

    @Test
    void returningAFaultedBoardClearsIt() {
        onBench(unit -> {
            Inventory.faultBoard(unit, "bx-14", WHEN, "chain went away", "runner");
            Upkeep.returnToService(unit, "bx-14", WHEN, "sam", Upkeep.REPAIR, "", 1);
            assertThat(unit.boards().state("bx-14").state()).isEqualTo(BoardStatus.AVAILABLE);
            assertThat(unit.maintenance().book().faultsClearedFor("bx-14")).isEqualTo(1);
        });
    }

    @Test
    void aCalibrationVisitCanBeTheThingThatReturnsIt() {
        onBench(unit -> {
            Inventory.moveState(unit, "bx-14", BoardStatus.MAINTENANCE, WHEN, "due", "sam");
            Upkeep.returnToService(unit, "bx-14", WHEN, "sam", "calibration", "", 0);
            assertThat(Upkeep.calibration(unit, "bx-14", WHEN).valid()).isTrue();
        });
    }

    @Test
    void aBoardWithTwoConvertersStillNeedsOneCalibration() {
        onBench(unit -> {
            Inventory.addBoard(
                    unit,
                    BoardSetup.kintex("bx-16")
                            .location("rack-2/shelf-05/slot-1")
                            .serial("210308FF9999")
                            .outlet(11)
                            .slots(
                                    new BoardSlot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b"),
                                    new BoardSlot("fmc1", "fmc-hpc", "fmc-adc-4ch-14b"))
                            .build(),
                    WHEN.minus(Duration.ofDays(10)));
            visit(unit, "bx-16", "calibration", WHEN.minus(Duration.ofDays(1)), "");
            assertThat(Upkeep.calibration(unit, "bx-16", WHEN).valid()).isTrue();
        });
    }
}
