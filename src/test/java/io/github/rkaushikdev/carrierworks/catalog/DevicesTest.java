package io.github.rkaushikdev.carrierworks.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.NotFound;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DevicesTest {

    @Test
    void aPartNumberFindsItsSilicon() {
        DeviceSpec spec = Devices.deviceFor(PartNumber.parse("XC7A100T-2FGG484I"));
        assertThat(spec.device()).isEqualTo("xc7a100t");
        assertThat(spec.family()).isEqualTo("artix7");
        assertThat(spec.luts()).isEqualTo(63400);
    }

    @Test
    void aBareDeviceCodeWorksToo() {
        assertThat(Devices.deviceFor("XC7K325T").idcode()).isEqualTo(0x03651093L);
    }

    @Test
    void anUnstockedDeviceIsANotFound() {
        NotFound caught = catchThrowableOfType(NotFound.class, () -> Devices.deviceFor("xc7v2000t"));
        assertThat(caught.kind()).isEqualTo("device");
    }

    @Test
    void lookupReturnsNothingRatherThanRaising() {
        assertThat(Devices.lookup("xc7v2000t")).isEmpty();
        assertThat(Devices.lookup(" XC7A35T ")).isPresent();
    }

    @Test
    void idcodeLookupIgnoresTheRevisionNibble() {
        DeviceSpec plain = Devices.forIdcode(0x03631093L);
        DeviceSpec stepped = Devices.forIdcode(0x13631093L);
        assertThat(plain).isSameAs(stepped);
        assertThat(plain.device()).isEqualTo("xc7a100t");
    }

    @Test
    void anUnknownIdcodeReportsTheValueItSaw() {
        NotFound caught = catchThrowableOfType(NotFound.class, () -> Devices.forIdcode(0x0BAD0093L));
        assertThat(caught.key()).isEqualTo("0x0bad0093");
    }

    @Test
    void matchesIdcodeAgreesWithTheLookup() {
        DeviceSpec spec = Devices.deviceFor("xc7a35t");
        assertThat(spec.matchesIdcode(0x4362D093L)).isTrue();
        assertThat(spec.matchesIdcode(0x03631093L)).isFalse();
    }

    @Test
    void configBytesRoundUp() {
        DeviceSpec spec = Devices.deviceFor("xc7a100t");
        assertThat(spec.configBits()).isEqualTo(30_606_304L);
        assertThat(spec.configBytes()).isEqualTo(3_825_788L);
    }

    @Test
    void blockRamIsReportedInBitsAsWell() {
        assertThat(Devices.deviceFor("xc7a35t").blockRamBits()).isEqualTo(50L * 36 * 1024);
    }

    @Test
    void aDesignThatFitsIsAccepted() {
        DeviceSpec spec = Devices.deviceFor("xc7a100t");
        assertThat(spec.holds(40000, 51000, 90, 120)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"70000, 1000, 1, 1", "1000, 200000, 1, 1", "1000, 1000, 400, 1", "1000, 1000, 1, 900"})
    void oneResourceOverIsEnoughToRefuse(int luts, int flipFlops, int brams, int dsps) {
        DeviceSpec spec = Devices.deviceFor("xc7a100t");
        assertThat(spec.holds(luts, flipFlops, brams, dsps)).isFalse();
    }

    @Test
    void familiesGroupTheWayThePartNumbersSay() {
        Set<String> artix = new HashSet<>();
        Devices.inFamily("artix7").forEach(spec -> artix.add(spec.device()));
        assertThat(artix).containsExactlyInAnyOrder("xc7a35t", "xc7a50t", "xc7a100t", "xc7a200t");
        assertThat(Devices.inFamily("stratix10")).isEmpty();
    }

    @Test
    void everyCatalogFamilyMatchesThePartNumberGrammar() {
        for (DeviceSpec spec : Devices.all()) {
            PartNumber part = PartNumber.parse(spec.device().toUpperCase(Locale.ROOT) + "-1FGG484C");
            assertThat(part.family()).isEqualTo(spec.family());
        }
    }

    @Test
    void theSmallZynqIsStocked() {
        DeviceSpec spec = Devices.deviceFor("xc7z010");
        assertThat(spec.family()).isEqualTo("zynq7000");
        assertThat(spec.luts()).isEqualTo(17_600);
        assertThat(spec.configBytes()).isEqualTo(2_083_740);
        assertThat(Devices.forIdcode(0x13722093L)).isSameAs(spec);
        assertThat(Devices.inFamily("zynq7000")).extracting(DeviceSpec::device).containsExactly("xc7z010", "xc7z020");
    }

    @Test
    void theDevicesComeOutOfThePartsFile() {
        assertThat(CatalogFile.parts("devices")).hasSameSizeAs(Devices.all());
        assertThat(Devices.all().getFirst().device()).isEqualTo("xc7s25");
    }

    @Test
    void idcodesAreUniqueOnceMasked() {
        List<Long> masked = Devices.all().stream()
                .map(spec -> spec.idcode() & Devices.IDCODE_MASK)
                .toList();
        assertThat(new HashSet<>(masked)).hasSameSizeAs(masked);
    }

    @Test
    void everyDeviceHasAPlausibleIrLength() {
        for (DeviceSpec spec : Devices.all()) {
            assertThat(spec.irLength()).isIn(6, 10);
            assertThat(spec.configBits()).isGreaterThan(1_000_000L);
        }
    }
}
