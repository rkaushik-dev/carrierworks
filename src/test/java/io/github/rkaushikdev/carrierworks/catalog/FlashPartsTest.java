package io.github.rkaushikdev.carrierworks.catalog;

import static io.github.rkaushikdev.carrierworks.Measure.mib;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.CatalogError;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FlashPartsTest {

    @Test
    void aPartIsFoundByName() {
        FlashSpec spec = FlashParts.part("N25Q128A13");
        assertThat(spec.vendor()).isEqualTo("micron");
        assertThat(spec.capacityBytes()).isEqualTo(mib(16));
        assertThat(spec.sectorBytes()).isEqualTo(65536);
    }

    @Test
    void anUnstockedPartIsANotFound() {
        assertThat(FlashParts.lookup("at45db161")).isEmpty();
        assertThatThrownBy(() -> FlashParts.part("at45db161")).isInstanceOf(NotFound.class);
    }

    @Test
    void aPartIsFoundByWhatItAnswered() {
        assertThat(FlashParts.forJedecId(0x20BA18).name()).isEqualTo("n25q128a13");
        NotFound caught = catchThrowableOfType(NotFound.class, () -> FlashParts.forJedecId(0x123456));
        assertThat(caught.key()).isEqualTo("0x123456");
    }

    @Test
    void manufacturerIdIsTheTopByte() {
        assertThat(FlashParts.part("mx25l25635f").manufacturerId()).isEqualTo(0xC2);
    }

    @Test
    void threeByteAddressingStopsAtSixteenMegabytes() {
        assertThat(FlashParts.part("n25q128a13").addressBytes()).isEqualTo(3);
        assertThat(FlashParts.part("n25q256a11").addressBytes()).isEqualTo(4);
        assertThat(FlashParts.part("w25q64jv").addressBytes()).isEqualTo(3);
    }

    @Test
    void geometryDividesTheWayTheDatasheetSays() {
        FlashSpec spec = FlashParts.part("n25q128a13");
        assertThat(spec.sectorCount()).isEqualTo(256);
        assertThat(spec.subsectorsPerSector()).isEqualTo(16);
    }

    @Test
    void sectorAndSubsectorLookups() {
        FlashSpec spec = FlashParts.part("n25q128a13");
        assertThat(spec.sectorOf(0)).isZero();
        assertThat(spec.sectorOf(65535)).isZero();
        assertThat(spec.sectorOf(65536)).isEqualTo(1);
        assertThat(spec.sectorBase(70000)).isEqualTo(65536);
        assertThat(spec.subsectorOf(4097)).isEqualTo(1);
    }

    @Test
    void anAddressPastTheEndIsRefused() {
        FlashSpec spec = FlashParts.part("w25q64jv");
        assertThatThrownBy(() -> spec.sectorOf(mib(8))).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> spec.sectorOf(-1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void alignmentQuestions() {
        FlashSpec spec = FlashParts.part("n25q128a13");
        assertThat(spec.isSectorAligned(0x40000)).isTrue();
        assertThat(spec.isSectorAligned(0x40100)).isFalse();
        assertThat(spec.isSubsectorAligned(0x41000)).isTrue();
        assertThat(spec.isSubsectorAligned(0x41001)).isFalse();
    }

    @Test
    void holdsIsAboutCapacityNotAddresses() {
        FlashSpec spec = FlashParts.part("w25q64jv");
        assertThat(spec.holds(mib(8))).isTrue();
        assertThat(spec.holds(mib(9))).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"0, 0, 0", "0, 1, 1", "0, 256, 1", "0, 257, 2", "255, 2, 2", "100, 156, 1", "100, 157, 2"})
    void pageSpansCountThePartialFirstPage(long address, long length, long pages) {
        assertThat(FlashParts.part("n25q128a13").pagesSpanned(address, length)).isEqualTo(pages);
    }

    @Test
    void aNegativeLengthIsABug() {
        assertThatThrownBy(() -> FlashParts.part("n25q128a13").pagesSpanned(0, -1))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void geometryThatDoesNotDivideIsRefusedAtImportTime() {
        assertThatThrownBy(() -> new FlashSpec("bad", "nobody", 0x000001, 1000, 256, 4096, 65536, 1, 1, 1, 1))
                .isInstanceOf(CatalogError.class);
    }

    @Test
    void everyCataloguedPartHasSaneNumbers() {
        for (FlashSpec spec : FlashParts.all()) {
            assertThat(spec.capacityBytes()).isGreaterThanOrEqualTo(mib(8));
            assertThat(spec.pageBytes()).isEqualTo(256);
            assertThat(spec.subsectorEraseMs()).isLessThan(spec.sectorEraseMs() * 4);
            assertThat(spec.bulkEraseS()).isPositive();
            assertThat(spec.eraseCycles()).isGreaterThanOrEqualTo(100_000);
        }
    }

    @Test
    void theOlderCarriersPartIsOneBitWideAndErasesOnlyWholeSectors() {
        FlashSpec part = FlashParts.part("m25p128");
        assertThat(part.quadCapable()).isFalse();
        assertThat(part.subsectorBytes()).isEqualTo(part.sectorBytes());
        assertThat(part.subsectorsPerSector()).isEqualTo(1);
        assertThat(part.sectorCount()).isEqualTo(64);
        assertThat(FlashParts.forJedecId(0x202018)).isSameAs(part);
    }

    @Test
    void theBigSpansionPartIsStocked() {
        FlashSpec part = FlashParts.forJedecId(0x010219);
        assertThat(part.name()).isEqualTo("s25fl256s");
        assertThat(part.addressBytes()).isEqualTo(4);
        assertThat(part.quadCapable()).isTrue();
        assertThat(part.eraseCycles()).isEqualTo(FlashSpec.DEFAULT_ERASE_CYCLES);
        assertThat(FlashParts.all()).hasSameSizeAs(CatalogFile.parts("flash"));
    }

    @Test
    void jedecIdsAreUnique() {
        List<Integer> ids = FlashParts.all().stream().map(FlashSpec::jedecId).toList();
        assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
    }
}
