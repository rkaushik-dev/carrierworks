package io.github.rkaushikdev.carrierworks.flash;

import static io.github.rkaushikdev.carrierworks.Measure.mib;
import static io.github.rkaushikdev.carrierworks.flash.RegionPurpose.DATA;
import static io.github.rkaushikdev.carrierworks.flash.RegionPurpose.GOLDEN;
import static io.github.rkaushikdev.carrierworks.flash.RegionPurpose.UPDATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.FlashParts;
import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LayoutTest {

    private static FlashSpec part() {
        return FlashParts.part("n25q128a13");
    }

    @Test
    void aRegionKnowsItsOwnExtent() {
        FlashRegion region = new FlashRegion("golden", 0, 0x400000, GOLDEN);
        assertThat(region.end()).isEqualTo(0x400000);
        assertThat(region.contains(0)).isTrue();
        assertThat(region.contains(0x3FFFFF)).isTrue();
        assertThat(region.contains(0x400000)).isFalse();
        assertThat(region.holds(1000)).isTrue();
        assertThat(region.holds(0x400001)).isFalse();
    }

    @Test
    void aRegionDescribesItself() {
        FlashRegion region = new FlashRegion("update", 0x400000, mib(4), UPDATE);
        assertThat(region.describe()).isEqualTo("update 0x00400000 +4 MiB (update)");
    }

    @ParameterizedTest
    @CsvSource({"-1, 100, data", "0, 0, data", "0, 100, spare"})
    void aNonsenseRegionIsRefused(long start, long length, String purpose) {
        assertThatThrownBy(() -> new FlashRegion("bad", start, length, RegionPurpose.parse(purpose)))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void theBenchSplitsEveryPartInHalves() {
        FlashLayout small = FlashLayout.halves(part());
        assertThat(small.region("golden").start()).isZero();
        assertThat(small.region("golden").length()).isEqualTo(mib(8));
        assertThat(small.region("update").start()).isEqualTo(mib(8));
        assertThat(small.region("update").end()).isEqualTo(mib(16));
        FlashLayout big = FlashLayout.halves(FlashParts.part("n25q256a11"));
        assertThat(big.region("update").start()).isEqualTo(mib(16));
        assertThat(big.free()).isZero();
    }

    @Test
    void aSingleImageLayoutRoundsUpToWholeSectors() {
        FlashRegion golden = FlashLayout.singleImage(part(), 3_825_788).region("golden");
        assertThat(golden.start()).isZero();
        assertThat(golden.length() % part().sectorBytes()).isZero();
        assertThat(golden.length()).isGreaterThanOrEqualTo(3_825_788);
    }

    @Test
    void anImageBiggerThanThePartIsRefused() {
        assertThatThrownBy(() -> FlashLayout.singleImage(part(), mib(20))).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void anEmptyImageIsRefused() {
        assertThatThrownBy(() -> FlashLayout.singleImage(part(), 0)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aMultibootLayoutPutsTheGoldenImageAtZero() {
        FlashLayout layout = FlashLayout.multiboot(part(), mib(4), mib(4));
        assertThat(layout.region("golden").start()).isZero();
        assertThat(layout.region("update").start()).isGreaterThanOrEqualTo(mib(4));
    }

    @Test
    void theUpdateAddressCanBePinnedBecauseTheGoldenImageKnowsIt() {
        FlashLayout layout = FlashLayout.multiboot(part(), mib(4), mib(4), 0x800000);
        assertThat(layout.region("update").start()).isEqualTo(0x800000);
    }

    @Test
    void anUpdateAddressOffASectorBoundaryIsRefused() {
        assertThatThrownBy(() -> FlashLayout.multiboot(part(), mib(4), mib(4), 0x800100))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aPairThatDoesNotFitIsRefused() {
        assertThatThrownBy(() -> FlashLayout.multiboot(part(), mib(8), mib(8), 0xA00000))
                .isInstanceOf(ConfigurationError.class);
    }

    @Test
    void theDefaultGapLeavesASpareSectorBetweenTheImages() {
        FlashLayout layout = FlashLayout.multiboot(part(), mib(4), mib(4));
        FlashRegion golden = layout.region("golden");
        FlashRegion update = layout.region("update");
        assertThat(update.start()).isEqualTo(golden.end() + part().sectorBytes());
    }

    @Test
    void regionsCannotOverlap() {
        List<FlashRegion> regions = List.of(
                new FlashRegion("golden", 0, mib(4), GOLDEN), new FlashRegion("update", mib(2), mib(4), UPDATE));
        assertThatThrownBy(() -> new FlashLayout(part(), regions)).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void regionsCannotShareASectorEvenWithoutOverlapping() {
        FlashSpec spec = part();
        List<FlashRegion> regions = List.of(
                new FlashRegion("golden", 0, spec.sectorBytes() + 16, GOLDEN),
                new FlashRegion("params", spec.sectorBytes() + 32, 64, DATA));
        ConfigurationError caught =
                catchThrowableOfType(ConfigurationError.class, () -> new FlashLayout(spec, regions));
        assertThat(caught.message()).contains("share a sector");
    }

    @Test
    void aRegionMayStartPartWayThroughASectorIfItOwnsIt() {
        FlashLayout layout = new FlashLayout(part(), List.of(new FlashRegion("params", 0x100, 64, DATA)));
        assertThat(layout.region("params").start()).isEqualTo(0x100);
        assertThat(layout.sectorSpan("params")).isEqualTo(new SectorSpan(0, 0));
    }

    @Test
    void aRegionOffTheEndOfThePartIsRefused() {
        List<FlashRegion> regions = List.of(new FlashRegion("golden", 0, mib(20), GOLDEN));
        assertThatThrownBy(() -> new FlashLayout(part(), regions)).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void twoRegionsCannotShareAName() {
        List<FlashRegion> regions = List.of(
                new FlashRegion("golden", 0, mib(1), GOLDEN), new FlashRegion("golden", mib(2), mib(1), UPDATE));
        assertThatThrownBy(() -> new FlashLayout(part(), regions)).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void anEmptyLayoutIsRefused() {
        assertThatThrownBy(() -> new FlashLayout(part(), List.of())).isInstanceOf(ConfigurationError.class);
    }

    @Test
    void aRegionNobodyNamedIsANotFound() {
        FlashLayout layout = FlashLayout.singleImage(part(), mib(1));
        assertThatThrownBy(() -> layout.region("update")).isInstanceOf(NotFound.class);
    }

    @Test
    void theLayoutFindsTheRegionAtAnAddress() {
        FlashLayout layout = FlashLayout.multiboot(part(), mib(4), mib(4), 0x800000);
        assertThat(layout.regionAt(0x10)).map(FlashRegion::name).contains("golden");
        assertThat(layout.regionAt(0x800010)).map(FlashRegion::name).contains("update");
        assertThat(layout.regionAt(0xF00000)).isEmpty();
    }

    @Test
    void usedAndFreeAddUpToThePart() {
        FlashLayout layout = FlashLayout.multiboot(part(), mib(4), mib(4), 0x800000);
        assertThat(layout.used() + layout.free()).isEqualTo(part().capacityBytes());
    }

    @Test
    void theSectorSpanOfARegionIsWhatAnEraseNeeds() {
        FlashLayout layout = FlashLayout.multiboot(part(), mib(4), mib(4), 0x800000);
        assertThat(layout.sectorSpan("update")).isEqualTo(new SectorSpan(128, 191));
        assertThat(layout.sectorsFor("update")).isEqualTo(64);
        assertThat(layout.sectorSpan("golden")).isEqualTo(new SectorSpan(0, 63));
    }

    @Test
    void theLayoutDescribesItselfInOrder() {
        String described =
                FlashLayout.multiboot(part(), mib(4), mib(4), 0x800000).describe();
        assertThat(described).startsWith("n25q128a13: golden 0x00000000");
        assertThat(described).contains("update 0x00800000");
    }
}
