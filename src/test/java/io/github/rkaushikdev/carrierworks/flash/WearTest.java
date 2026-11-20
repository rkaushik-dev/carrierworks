package io.github.rkaushikdev.carrierworks.flash;

import static io.github.rkaushikdev.carrierworks.Measure.kib;
import static io.github.rkaushikdev.carrierworks.Measure.mib;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.withinPercentage;

import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.FlashParts;
import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WearTest {

    @TempDir
    Path tmp;

    private static FlashSpec part() {
        return FlashParts.part("n25q128a13");
    }

    private static FlashLayout layout() {
        return FlashLayout.multiboot(part(), mib(4), mib(4), 0x800000);
    }

    private WearLedger ledger() {
        return new WearLedger(tmp.resolve("wear.json"));
    }

    private static byte[] ones(long length) {
        byte[] data = new byte[Math.toIntExact(length)];
        Arrays.fill(data, (byte) 0x01);
        return data;
    }

    private static FlashPlan write(String region, long length) {
        return FlashPlan.forWrite(layout(), region, ones(length));
    }

    @Test
    void aWriteCountsOneErasePerSectorItTouches() {
        WearLedger book = ledger();
        int touched = book.record("bx-14", write("update", kib(64) + 1));
        assertThat(touched).isEqualTo(2);
        assertThat(book.erases("bx-14", 128)).isEqualTo(1);
        assertThat(book.erases("bx-14", 129)).isEqualTo(1);
        assertThat(book.erases("bx-14", 130)).isZero();
    }

    @Test
    void erasesAccumulateOverProgrammingRuns() {
        WearLedger book = ledger();
        for (int run = 0; run < 3; run++) {
            book.record("bx-14", write("update", kib(4)));
        }
        assertThat(book.erases("bx-14", 128)).isEqualTo(3);
        assertThat(book.total("bx-14")).isEqualTo(3);
    }

    @Test
    void sectorZeroTakesTheWearBecauseEveryImageStartsThere() {
        WearLedger book = ledger();
        for (int run = 0; run < 10; run++) {
            book.record("bx-14", write("golden", kib(4)));
            book.record("bx-14", write("update", kib(4)));
        }
        assertThat(book.erases("bx-14", 0)).isEqualTo(10);
        assertThat(book.erases("bx-14", 128)).isEqualTo(10);
        assertThat(book.erases("bx-14", 64)).isZero();
    }

    @Test
    void aWholeRegionEraseCountsEverySector() {
        WearLedger book = ledger();
        book.record("bx-14", FlashPlan.forErase(layout(), "golden"));
        assertThat(book.total("bx-14")).isEqualTo(64);
        assertThat(book.erases("bx-14", 63)).isEqualTo(1);
    }

    @Test
    void subsectorErasesInsideOneSectorAllCountAgainstIt() {
        WearLedger book = ledger();
        book.record("bx-14", FlashPlan.forWrite(layout(), "update", ones(kib(12)), EraseUnit.SUBSECTOR, true));
        assertThat(book.erases("bx-14", 128)).isEqualTo(3);
    }

    @Test
    void boardsAreCountedSeparately() {
        WearLedger book = ledger();
        book.record("bx-14", write("update", kib(4)));
        book.record("bx-15", write("update", kib(4)));
        assertThat(book.boards()).containsExactly("bx-14", "bx-15");
        assertThat(book.total("bx-15")).isEqualTo(1);
    }

    @Test
    void assetTagsAreNormalisedSoOneBoardIsOneRow() {
        WearLedger book = ledger();
        book.record("BX-0014", write("update", kib(4)));
        book.record("bx-14", write("update", kib(4)));
        assertThat(book.boards()).containsExactly("bx-14");
        assertThat(book.total("bx-14")).isEqualTo(2);
    }

    @Test
    void theLedgerSurvivesARestart() {
        WearLedger book = ledger();
        book.record("bx-14", write("update", kib(4)));
        book.save();
        WearLedger reopened = new WearLedger(tmp.resolve("wear.json"));
        assertThat(reopened.erases("bx-14", 128)).isEqualTo(1);
        assertThat(reopened.boards()).containsExactly("bx-14");
    }

    @Test
    void aLedgerWithNoFileYetIsEmpty() {
        assertThat(ledger().boards()).isEmpty();
        assertThat(ledger().total("bx-14")).isZero();
    }

    @Test
    void wearIsReportedMostWornFirst() {
        WearLedger book = ledger();
        for (int run = 0; run < 4; run++) {
            book.record("bx-14", write("golden", kib(4)));
        }
        book.record("bx-14", write("update", kib(4)));
        List<SectorWear> wear = book.wear("bx-14", part());
        assertThat(wear).extracting(SectorWear::sector).containsExactly(0L, 128L);
        assertThat(wear.getFirst().erases()).isEqualTo(4);
        assertThat(wear.getFirst().describe()).isEqualTo("sector 0: 4 of 100000 erases");
    }

    @Test
    void onlyTheHottestSectorsComeBackWhenAsked() {
        WearLedger book = ledger();
        book.record("bx-14", FlashPlan.forErase(layout(), "golden"));
        assertThat(book.hottest("bx-14", part(), 3)).hasSize(3);
        assertThatThrownBy(() -> book.hottest("bx-14", part(), 0)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aFreshBoardHasNothingWorn() {
        WearLedger book = ledger();
        book.record("bx-14", write("update", kib(4)));
        assertThat(book.wornSectors("bx-14", part())).isEmpty();
        assertThat(book.isWorn("bx-14", part())).isFalse();
    }

    @Test
    void aSectorPastFourFifthsOfItsRatingIsWorn() {
        SectorWear entry = new SectorWear(0, 80_000, 100_000);
        assertThat(entry.worn()).isTrue();
        assertThat(entry.remaining()).isEqualTo(20_000);
        assertThat(entry.fraction()).isCloseTo(0.8, withinPercentage(1e-4));
    }

    @Test
    void aSectorBelowTheLineIsNotWorn() {
        assertThat(new SectorWear(0, 79_999, 100_000).worn()).isFalse();
    }

    @Test
    void aSectorPastItsRatingHasNothingLeft() {
        SectorWear entry = new SectorWear(0, 120_000, 100_000);
        assertThat(entry.remaining()).isZero();
        assertThat(entry.worn()).isTrue();
    }

    @Test
    void aNegativeSectorIsABug() {
        assertThatThrownBy(() -> ledger().erases("bx-14", -1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aWornBoardIsReportedFromTheLedger() {
        WearLedger book = ledger();
        book.restore("bx-14", Map.of(0L, 90_000L, 1L, 12L));
        assertThat(book.isWorn("bx-14", part())).isTrue();
        assertThat(book.wornSectors("bx-14", part()))
                .extracting(SectorWear::sector)
                .containsExactly(0L);
    }

    @Test
    void restoredHistoryCarriesOnBeingCounted() {
        WearLedger book = ledger();
        book.restore("bx-14", Map.of(0L, 400L));
        book.record("bx-14", write("golden", kib(4)));
        assertThat(book.erases("bx-14", 0)).isEqualTo(401);
    }

    @Test
    void restoringNonsenseIsRefused() {
        assertThatThrownBy(() -> ledger().restore("bx-14", Map.of(-1L, 4L))).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> ledger().restore("bx-14", Map.of(0L, -4L))).isInstanceOf(ValidationError.class);
    }
}
