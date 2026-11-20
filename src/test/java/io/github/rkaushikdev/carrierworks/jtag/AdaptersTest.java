package io.github.rkaushikdev.carrierworks.jtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.CatalogError;
import io.github.rkaushikdev.carrierworks.Frequency;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class AdaptersTest {

    private static AdapterSpec hs2() {
        return Adapters.model("jtag-hs2");
    }

    @Test
    void aModelIsFoundByName() {
        assertThat(hs2().vendor()).isEqualTo("digilent");
        assertThat(hs2().maxClock()).isEqualTo(Frequency.megahertz(30));
    }

    @Test
    void anUnknownModelIsANotFound() {
        assertThatThrownBy(() -> Adapters.model("wiggler")).isInstanceOf(NotFound.class);
    }

    @Test
    void voltageSupportIsAMembershipQuestion() {
        assertThat(hs2().drives(1800)).isTrue();
        assertThat(hs2().drives(5000)).isTrue();
        assertThat(hs2().drives(1200)).isFalse();
        assertThat(Adapters.model("ft2232h-mini").lowestVoltageMv()).isEqualTo(3300);
    }

    @Test
    void aUnitNormalisesItsSerialAndChecksItsModel() {
        AdapterUnit unit = new AdapterUnit("210308ab1234", "JTAG HS2", "usb-3.1");
        assertThat(unit.serial()).isEqualTo("210308AB1234");
        assertThat(unit.model()).isEqualTo("jtag-hs2");
        assertThat(unit.spec().vendor()).isEqualTo("digilent");
        assertThat(unit.describe()).isEqualTo("jtag-hs2 210308AB1234 on usb-3.1");
    }

    @Test
    void aUnitOfAModelNobodyStocksIsRefused() {
        assertThatThrownBy(() -> new AdapterUnit("210308AB1234", "wiggler")).isInstanceOf(NotFound.class);
    }

    @Test
    void aUnitWithAJunkSerialIsRefused() {
        assertThatThrownBy(() -> new AdapterUnit("nope!", "jtag-hs2")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aUnitWithoutAPortDescribesItselfAnyway() {
        assertThat(new AdapterUnit("210308AB1234", "jtag-hs2").describe()).isEqualTo("jtag-hs2 210308AB1234");
    }

    @Test
    void aSingleDeviceChainRunsAtTheLowestCeiling() {
        assertThat(Adapters.negotiateClock(hs2(), 1, 30_000_000)).isEqualTo(Frequency.megahertz(30));
        assertThat(Adapters.negotiateClock(hs2(), 1, 10_000_000)).isEqualTo(Frequency.megahertz(10));
    }

    @Test
    void theBoardGetsASayAsWell() {
        assertThat(Adapters.negotiateClock(hs2(), 1, 30_000_000, 6_000_000)).isEqualTo(Frequency.megahertz(6));
    }

    @Test
    void theAdapterCeilingAppliesEvenWhenTheSiteAllowsMore() {
        assertThat(Adapters.negotiateClock(Adapters.model("platform-cable-ii"), 1, 30_000_000))
                .isEqualTo(Frequency.megahertz(24));
    }

    @ParameterizedTest
    @CsvSource({
        "1, 30000000",
        "2, 24000000",
        "3, 18000000",
        "4, 12000000",
        "5, 6000000",
        "6, 6000000",
        "9, 6000000",
    })
    void everyExtraDeviceCostsAFifthOfTheClock(int devices, long hertz) {
        assertThat(Adapters.negotiateClock(hs2(), devices, 30_000_000).hertz()).isEqualTo(hertz);
    }

    @Test
    void deratingNeverFallsThroughTheFloor() {
        assertThat(Adapters.negotiateClock(hs2(), 12, 30_000_000).hertz()).isGreaterThanOrEqualTo(1_000_000);
    }

    @Test
    void aCeilingAlreadyBelowTheFloorIsNotRaisedToIt() {
        assertThat(Adapters.negotiateClock(hs2(), 8, 500_000).hertz()).isEqualTo(500_000);
    }

    static Stream<Arguments> nonsenseNegotiationInputsAreRefused() {
        return Stream.of(
                Arguments.of(0, 30_000_000L, null), Arguments.of(1, 0L, null), Arguments.of(1, 30_000_000L, 0L));
    }

    @ParameterizedTest
    @MethodSource
    void nonsenseNegotiationInputsAreRefused(int devices, long site, Long board) {
        assertThatThrownBy(() -> {
                    if (board == null) {
                        Adapters.negotiateClock(hs2(), devices, site);
                    } else {
                        Adapters.negotiateClock(hs2(), devices, site, board);
                    }
                })
                .isInstanceOf(ValidationError.class);
    }

    static Stream<Arguments> aNonsenseAdapterIsRefused() {
        return Stream.of(
                Arguments.of(0, List.of(3300), 1),
                Arguments.of(30_000_000, List.of(), 1),
                Arguments.of(30_000_000, List.of(3300), 0));
    }

    @ParameterizedTest
    @MethodSource
    void aNonsenseAdapterIsRefused(int clock, List<Integer> voltages, int channels) {
        assertThatThrownBy(() -> new AdapterSpec("bad", "nobody", clock, voltages, channels, true, true))
                .isInstanceOf(CatalogError.class);
    }

    @Test
    void theCatalogHoldsTogether() {
        List<String> models = Adapters.all().stream().map(AdapterSpec::model).toList();
        assertThat(new HashSet<>(models)).hasSameSizeAs(models);
        for (AdapterSpec spec : Adapters.all()) {
            assertThat(spec.maxClockHz()).isGreaterThanOrEqualTo(1_000_000);
            assertThat(spec.voltagesMv()).allMatch(level -> 1000 <= level && level <= 5000);
        }
    }

    @Test
    void oneModelInTheCatalogCannotDoFlash() {
        assertThat(Adapters.model("arm-usb-tiny-h").canProgramFlash()).isFalse();
        assertThat(hs2().canProgramFlash()).isTrue();
    }
}
