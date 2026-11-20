package io.github.rkaushikdev.carrierworks.inventory;

import static io.github.rkaushikdev.carrierworks.inventory.MaintenanceKind.CALIBRATION;
import static io.github.rkaushikdev.carrierworks.inventory.MaintenanceKind.INSPECTION;
import static io.github.rkaushikdev.carrierworks.inventory.MaintenanceKind.REPAIR;
import static io.github.rkaushikdev.carrierworks.inventory.MaintenanceKind.UPGRADE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MaintenanceTest {

    private static final Instant NOW = Instant.parse("2025-07-25T10:00:00Z");

    private static MaintenanceBook book() {
        return new MaintenanceBook();
    }

    private static Instant ago(Duration span) {
        return NOW.minus(span);
    }

    @Test
    void aRecordSaysWhoDidWhatAndWhen() {
        MaintenanceRecord entry = new MaintenanceRecord("BX-0014", REPAIR, NOW, "dana", "reflowed the jtag header", 0);
        assertThat(entry.board()).isEqualTo("bx-14");
        assertThat(entry.describe()).isEqualTo("bx-14 repair on 2025-07-25 by dana: reflowed the jtag header");
    }

    @Test
    void aRecordWithoutNotesStillDescribesItself() {
        MaintenanceRecord entry = new MaintenanceRecord("bx-14", INSPECTION, NOW, "dana");
        assertThat(entry.describe()).isEqualTo("bx-14 inspection on 2025-07-25 by dana");
    }

    @ParameterizedTest
    @CsvSource({"exorcism, dana, 0", "repair, '  ', 0", "repair, dana, -1"})
    void aNonsenseRecordIsRefused(String kind, String by, int cleared) {
        assertThatThrownBy(() -> new MaintenanceRecord("bx-14", MaintenanceKind.parse(kind), NOW, by, "", cleared))
                .isInstanceOf(ValidationError.class);
    }

    /**
     * A maintenance time with no zone is refused. A record keeps its moment as an {@link Instant},
     * which is always UTC, so a zoneless time cannot be handed in; the test holds the type to that.
     */
    @Test
    void maintenanceTimesAreInUtc() {
        assertThat(Arrays.stream(MaintenanceRecord.class.getRecordComponents())
                        .filter(component -> component.getName().equals("at"))
                        .<Object>map(component -> component.getType()))
                .containsExactly(Instant.class);
    }

    @Test
    void recordsComeBackPerBoard() {
        MaintenanceBook live = book();
        live.add("bx-14", REPAIR, NOW, "dana");
        live.add("bx-15", INSPECTION, NOW, "sam");
        assertThat(live.forBoard("bx-14")).hasSize(1);
        assertThat(live.boards()).containsExactly("bx-14", "bx-15");
        assertThat(live.all()).hasSize(2);
    }

    @Test
    void theLastVisitOfAKindIsFound() {
        MaintenanceBook live = book();
        live.add("bx-14", REPAIR, ago(Duration.ofDays(40)), "dana");
        live.add("bx-14", CALIBRATION, ago(Duration.ofDays(10)), "sam");
        live.add("bx-14", REPAIR, ago(Duration.ofDays(2)), "dana");
        assertThat(live.last("bx-14").orElseThrow().kind()).isEqualTo(REPAIR);
        assertThat(live.last("bx-14", CALIBRATION).orElseThrow().by()).isEqualTo("sam");
        assertThat(live.last("bx-14", UPGRADE)).isEmpty();
        assertThat(live.last("bx-99")).isEmpty();
    }

    @Test
    void aBoardWithAConverterNeedsCalibrating() {
        assertThat(MaintenanceBook.calibrationInterval(BoardSetup.kintex()))
                .contains(MaintenanceBook.CALIBRATION_INTERVAL);
    }

    @Test
    void aBareCarrierDoesNot() {
        assertThat(MaintenanceBook.calibrationInterval(
                        BoardSetup.kintex("bx-14").slots().build()))
                .isEmpty();
    }

    @Test
    void aBoardWithOnlyADigitalCardDoesNot() {
        Board board = BoardSetup.artix("bx-15")
                .slots(new BoardSlot("pmod0", "pmod", "pmod-rs485"))
                .build();
        assertThat(MaintenanceBook.calibrationInterval(board)).isEmpty();
    }

    @Test
    void aBoardWithAConverterPmodDoes() {
        Board board = BoardSetup.artix("bx-15")
                .slots(new BoardSlot("pmod0", "pmod", "pmod-ad2"))
                .build();
        assertThat(MaintenanceBook.calibrationInterval(board)).contains(MaintenanceBook.CALIBRATION_INTERVAL);
    }

    @Test
    void aBoardThatNeedsCalibrationAndHasNeverHadAnyIsDueNow() {
        Board board = BoardSetup.kintex();
        MaintenanceBook live = book();
        assertThat(live.calibrationDue(board, NOW)).contains(NOW);
        assertThat(live.calibrationValid(board, NOW)).isFalse();
    }

    @Test
    void aFreshlyCalibratedBoardIsGoodForAYear() {
        Board board = BoardSetup.kintex();
        MaintenanceBook live = book();
        live.add("bx-14", CALIBRATION, ago(Duration.ofDays(30)), "sam");
        assertThat(live.calibrationDue(board, NOW))
                .contains(ago(Duration.ofDays(30)).plus(MaintenanceBook.CALIBRATION_INTERVAL));
        assertThat(live.calibrationValid(board, NOW)).isTrue();
    }

    @Test
    void aBoardCalibratedTooLongAgoIsNotValid() {
        Board board = BoardSetup.kintex();
        MaintenanceBook live = book();
        live.add("bx-14", CALIBRATION, ago(Duration.ofDays(400)), "sam");
        assertThat(live.calibrationValid(board, NOW)).isFalse();
    }

    @Test
    void aBoardThatNeedsNoCalibrationIsAlwaysValid() {
        Board bare = BoardSetup.kintex("bx-14").slots().build();
        MaintenanceBook live = book();
        assertThat(live.calibrationDue(bare, NOW)).isEmpty();
        assertThat(live.calibrationValid(bare, NOW)).isTrue();
    }

    @Test
    void fittingAConverterMakesABoardImmediatelyDue() {
        Board bare = BoardSetup.kintex("bx-14")
                .slots(new BoardSlot("fmc0", "fmc-hpc"))
                .build();
        MaintenanceBook live = book();
        live.add("bx-14", INSPECTION, ago(Duration.ofDays(5)), "dana");
        assertThat(live.calibrationValid(bare, NOW)).isTrue();
        Board fitted = bare.withCard("fmc0", "fmc-adc-4ch-14b");
        assertThat(live.calibrationValid(fitted, NOW)).isFalse();
    }

    @Test
    void overdueBoardsComeBackOldestFirst() {
        MaintenanceBook live = book();
        live.add("bx-14", CALIBRATION, ago(Duration.ofDays(500)), "sam");
        List<Board> boards = List.of(
                BoardSetup.kintex(),
                BoardSetup.artix("bx-15")
                        .slots(new BoardSlot("pmod0", "pmod", "pmod-ad2"))
                        .build(),
                BoardSetup.artix("bx-16")
                        .location("rack-4/shelf-02/slot-1")
                        .bare()
                        .slots()
                        .build());
        assertThat(live.overdue(boards, NOW)).extracting(Board::tag).containsExactly("bx-14", "bx-15");
    }

    @Test
    void clearedFaultsAreTotalledForABoard() {
        MaintenanceBook live = book();
        live.add("bx-14", REPAIR, ago(Duration.ofDays(9)), "dana", "", 3);
        live.add("bx-14", REPAIR, NOW, "dana", "", 2);
        assertThat(live.faultsClearedFor("bx-14")).isEqualTo(5);
        assertThat(live.faultsClearedFor("bx-15")).isZero();
    }
}
