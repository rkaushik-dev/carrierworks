package io.github.rkaushikdev.carrierworks.bitstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConfigOptionsTest {

    @Test
    void anImageThatSetsNothingLeavesTheDefaults() {
        ConfigOptions options = Images.bitstream().options();
        assertThat(options).isEqualTo(ConfigOptions.DEFAULTS);
        assertThat(options.readable()).isTrue();
        assertThat(options.watchdog()).isFalse();
        assertThat(options.describe()).isEqualTo("defaults");
    }

    @ParameterizedTest
    @CsvSource({
        "0x03, 1, 3",
        "0x0b, 1, 3",
        "0x3b, 2, 3",
        "0x6b, 4, 3",
        "0x13, 1, 4",
        "0x0c, 1, 4",
        "0x3c, 2, 4",
        "0x6c, 4, 4"
    })
    void theReadOpcodeSaysHowWideAndHowFar(String opcode, int width, int addressBytes) {
        SpiRead read = Images.image()
                .spiRead(Integer.decode(opcode))
                .bitstream()
                .options()
                .spiRead()
                .orElseThrow();
        assertThat(read.busWidth()).isEqualTo(width);
        assertThat(read.addressBytes()).isEqualTo(addressBytes);
        assertThat(read.needsQuad()).isEqualTo(width == 4);
    }

    @Test
    void onlyTheLowByteOfTheBspiWriteIsTheOpcode() {
        ConfigOptions options = Images.image().spiRead(0x0B00_006B).bitstream().options();
        assertThat(options.spiRead()).contains(SpiRead.QUAD_READ);
    }

    @Test
    void theLastBspiWriteWins() {
        ConfigOptions options =
                Images.image().spiRead(0x0B).spiRead(0x6C).bitstream().options();
        assertThat(options.spiRead()).contains(SpiRead.QUAD_READ_4B);
    }

    @Test
    void anOpcodeThatIsNotAReadIsRefused() {
        assertThatThrownBy(() -> Images.image().spiRead(0x02).bitstream().options())
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("not a spi read opcode");
    }

    @Test
    void aThreeByteReadCannotSeePastSixteenMegabytes() {
        assertThat(SpiRead.FAST_READ.reaches(0xFF_FFFFL)).isTrue();
        assertThat(SpiRead.FAST_READ.reaches(0x100_0000L)).isFalse();
        assertThat(SpiRead.FAST_READ_4B.reaches(0x100_0000L)).isTrue();
    }

    @Test
    void theDecryptorBitMakesTheImageUnreadable() {
        ConfigOptions options = Images.image().encrypted().bitstream().options();
        assertThat(options.encrypted()).isTrue();
        assertThat(options.readable()).isFalse();
    }

    @Test
    void aMaskedOutDecryptorBitIsNotEncryption() {
        ConfigOptions options = Images.image()
                .write(6, 0x0000_0008L)
                .write(5, 1L << 6)
                .bitstream()
                .options();
        assertThat(options.encrypted()).isFalse();
    }

    @Test
    void aControlWriteWithNoMaskCountsEveryBit() {
        ConfigOptions options = Images.image().write(5, 1L << 6).bitstream().options();
        assertThat(options.encrypted()).isTrue();
    }

    @Test
    void writingTheMultipleFrameRegisterMeansCompressed() {
        ConfigOptions options = Images.image().write(10, 0).bitstream().options();
        assertThat(options.compressed()).isTrue();
    }

    @Test
    void theWatchdogNeedsItsConfigurationBitAndACount() {
        assertThat(Images.image().watchdog(0x10_0000).bitstream().options().watchdogCycles())
                .hasValue(0x10_0000);
        assertThat(Images.image().watchdog(0).bitstream().options().watchdog()).isFalse();
        assertThat(Images.image().write(17, 0x10_0000).bitstream().options().watchdog())
                .isFalse();
    }

    @Test
    void theOptionsDescribeThemselvesInStreamOrder() {
        ConfigOptions options = Images.image()
                .spiRead(0x6B)
                .encrypted()
                .write(10, 0)
                .watchdog(0x10_0000)
                .bitstream()
                .options();
        assertThat(options.describe())
                .isEqualTo("x4 read 0x6b, 3 address bytes, encrypted, compressed, watchdog 0x00100000");
    }

    @Test
    void writesToOneRegisterComeBackInStreamOrder() {
        Bitstream image = Images.image().spiRead(0x0B).spiRead(0x6B).bitstream();
        assertThat(Packets.writtenTo(image.packets(), "bspi")).containsExactly(0x0BL, 0x6BL);
        assertThat(Packets.writtenTo(image.packets(), "cor1")).isEmpty();
    }

    @Test
    void theSpiReadCommandHasAName() {
        Bitstream image = Images.image().write(4, 18).bitstream();
        assertThat(image.commands()).containsExactly("bspi_read", "start");
    }
}
