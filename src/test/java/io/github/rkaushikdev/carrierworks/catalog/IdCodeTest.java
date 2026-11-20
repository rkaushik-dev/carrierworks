package io.github.rkaushikdev.carrierworks.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class IdCodeTest {

    @Test
    void anArtixIdcodeComesApart() {
        IdCode code = new IdCode(0x0362D093L);
        assertThat(code.manufacturer()).isEqualTo(0x049);
        assertThat(code.manufacturerName()).isEqualTo("xilinx");
        assertThat(code.part()).isEqualTo(0x362D);
        assertThat(code.version()).isZero();
        assertThat(code.hasValidLsb()).isTrue();
    }

    @Test
    void theVersionNibbleIsTheStepping() {
        IdCode stepped = new IdCode(0x4362D093L);
        assertThat(stepped.version()).isEqualTo(4);
        assertThat(stepped.part()).isEqualTo(new IdCode(0x0362D093L).part());
        assertThat(stepped.sameDevice(new IdCode(0x0362D093L))).isTrue();
    }

    @Test
    void withoutVersionClearsOnlyTheTopNibble() {
        assertThat(new IdCode(0xF362D093L).withoutVersion()).isEqualTo(new IdCode(0x0362D093L));
    }

    @Test
    void twoDifferentPartsAreNotTheSameDevice() {
        assertThat(new IdCode(0x0362D093L).sameDevice(new IdCode(0x03631093L))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0x0362D093", "0362d093", " 0x0362_d093 ", "0X0362D093"})
    void idcodesParseHoweverTheyWereWritten(String text) {
        assertThat(IdCode.parse(text)).isEqualTo(new IdCode(0x0362D093L));
    }

    @Test
    void parsingAnIntIsAllowedBecauseDriversReturnInts() {
        assertThat(IdCode.parse(0x0362D093L).value()).isEqualTo(0x0362D093L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not-an-idcode", "0xzz"})
    void junkIdcodesAreRefused(String text) {
        assertThatThrownBy(() -> IdCode.parse(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void anIdcodeWiderThanTheRegisterIsRefused() {
        assertThatThrownBy(() -> new IdCode(IdCode.ALL_ONES + 1)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new IdCode(-1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void buildingReproducesTheRealThing() {
        assertThat(IdCode.build(0x049, 0x362D, 0)).isEqualTo(new IdCode(0x0362D093L));
    }

    @ParameterizedTest
    @CsvSource({"0x800, 0x1234, 0", "0x049, 0x10000, 0", "0x049, 0x1234, 16", "-1, 0, 0"})
    void buildingOutOfRangeIsRefused(String manufacturer, String part, int version) {
        assertThatThrownBy(() -> IdCode.build(Integer.decode(manufacturer), Integer.decode(part), version))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void anOpenChainReadsAsAllOnesAndIsNotADevice() {
        IdCode floating = new IdCode(IdCode.ALL_ONES);
        assertThat(floating.isFloating()).isTrue();
        assertThat(floating.isPlausible()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {0x00000000L, 0x0362D092L, 0xFFFFFFFFL, 0x00000001L, 0x0000FFFFL})
    void wiringFaultsAreNotPlausibleDevices(long value) {
        assertThat(new IdCode(value).isPlausible()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {0x0362D093L, 0x4BA00477L, 0x01111043L})
    void realLookingIdcodesArePlausible(long value) {
        assertThat(new IdCode(value).isPlausible()).isTrue();
    }

    @Test
    void theArmDebugPortReportsAsArm() {
        assertThat(new IdCode(0x4BA00477L).manufacturerName()).isEqualTo("arm");
    }

    @Test
    void anUnmetManufacturerReportsItsNumber() {
        assertThat(IdCode.manufacturerName(0x111)).isEqualTo("unknown-0x111");
    }

    @Test
    void idcodesPrintAsPaddedHex() {
        assertThat(new IdCode(0x362D093L)).hasToString("0x0362d093");
    }

    @Test
    void everyCataloguedDeviceCarriesAXilinxIdcode() {
        for (DeviceSpec spec : Devices.all()) {
            IdCode code = new IdCode(spec.idcode());
            assertThat(code.manufacturerName()).isEqualTo("xilinx");
            assertThat(code.isPlausible()).isTrue();
            assertThat(code.version()).isZero();
        }
    }
}
