package io.github.rkaushikdev.carrierworks.bitstream;

import static io.github.rkaushikdev.carrierworks.bitstream.Packets.SYNC_WORD;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.type1;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.wordsToBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.DeviceMismatch;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageTest {

    private static final long ARTIX = 0x03631093L;
    private static final long KINTEX = 0x03651093L;

    /** An Artix image with an rcrc, a wcfg, a frame write, a start and a desync, each changeable. */
    private static Images.Image sample() {
        return Images.image()
                .part("7a100tfgg484")
                .idcode(ARTIX)
                .frames(8)
                .command(7)
                .command(1)
                .desync();
    }

    @Test
    void anImageReadsItsOwnHeaderAndPackets() {
        Bitstream image = Bitstream.read(sample().bitfile(), "radar.bit");
        assertThat(image.design()).isEqualTo("radar_frontend");
        assertThat(image.device()).isEqualTo("xc7a100t");
        assertThat(image.source()).isEqualTo("radar.bit");
        assertThat(image.commands()).containsExactly("rcrc", "wcfg", "start", "desync");
    }

    @Test
    void theDigestCoversTheImageAndNotTheHeader() {
        Bitstream first = sample().bitstream();
        Bitstream second = Bitstream.read(BitFile.build(
                "radar_frontend;Version=2024.1", "7a100tfgg484", "2026/01/01", "23:59:59", sample().configWords()));
        assertThat(first.digest()).isEqualTo(second.digest());
        assertThat(first.shortDigest()).isEqualTo(first.digest().substring(0, 12));
    }

    @Test
    void aDifferentImageGetsADifferentDigest() {
        assertThat(sample().bitstream().digest())
                .isNotEqualTo(sample().frames(9).bitstream().digest());
    }

    @Test
    void theDeclaredIdcodeIsReadOutOfThePackets() {
        Bitstream image = sample().bitstream();
        assertThat(image.declaredIdcode()).isPresent();
        assertThat(image.declaredIdcode().get().value()).isEqualTo(ARTIX);
    }

    @Test
    void aFullImageStartsTheDevice() {
        assertThat(sample().bitstream().isPartial()).isFalse();
    }

    @Test
    void aPartialImageDoesNot() {
        Bitstream image = sample().start(false).bitstream();
        assertThat(image.isPartial()).isTrue();
        assertThat(image.summary()).contains("partial");
    }

    @Test
    void aMultibootImageCarriesTheJumpCommand() {
        assertThat(sample().iprog(0x40_0000).bitstream().isMultiboot()).isTrue();
        assertThat(sample().bitstream().isMultiboot()).isFalse();
    }

    @Test
    void anImageChecksOutAgainstTheDeviceItIsFor() {
        assertThatCode(() -> sample().bitstream().checkFor("xc7a100t")).doesNotThrowAnyException();
    }

    @Test
    void anImageForAnotherDeviceIsRefusedOnItsIdcode() {
        Bitstream image = sample().idcode(KINTEX).bitstream();
        DeviceMismatch caught = catchThrowableOfType(DeviceMismatch.class, () -> image.checkFor("xc7a100t"));
        assertThat(caught.expected()).isEqualTo("xc7a100t");
        assertThat(caught.found()).isEqualTo("0x03651093");
    }

    @Test
    void theIdcodeWriteOutranksTheHeader() {
        // header says kintex, packets say artix, and the packets are believed
        Bitstream image = sample().part("7k325tffg900").idcode(ARTIX).bitstream();
        assertThatCode(() -> image.checkFor("xc7a100t")).doesNotThrowAnyException();
        assertThatThrownBy(() -> image.checkFor("xc7k325t")).isInstanceOf(DeviceMismatch.class);
    }

    @Test
    void anImageWithoutAnIdcodeWriteFallsBackToItsHeader() {
        byte[] data = BitFile.build("top", "7a100tfgg484", "d", "t", wordsToBytes(SYNC_WORD, type1(2, 4, 1), 5));
        Bitstream image = Bitstream.read(data);
        assertThat(image.declaredIdcode()).isEmpty();
        assertThatCode(() -> image.checkFor("xc7a100t")).doesNotThrowAnyException();
        assertThatThrownBy(() -> image.checkFor("xc7k325t")).isInstanceOf(DeviceMismatch.class);
    }

    @Test
    void theFillFractionSaysHowMuchOfTheDeviceTheImageCovers() {
        Bitstream image = sample().bitstream();
        assertThat(image.fills()).isGreaterThan(0.0).isLessThan(0.01);
        assertThat(image.spec()).isPresent();
    }

    @Test
    void anImageForADeviceNobodyStocksFillsNothing() {
        Bitstream image = sample().part("7v585tffg1157").bitstream();
        assertThat(image.spec()).isEmpty();
        assertThat(image.fills()).isEqualTo(0.0);
    }

    @Test
    void anEmptyImageIsRefused() {
        byte[] data = BitFile.build("top", "7a100tfgg484", "d", "t", new byte[0]);
        assertThatThrownBy(() -> Bitstream.read(data)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aFileIsLoadedFromDiskWithItsName(@TempDir Path tmp) throws IOException {
        Path path = tmp.resolve("radar_frontend.bit");
        Files.write(path, sample().bitfile());
        Bitstream image = Bitstream.load(path);
        assertThat(image.source()).isEqualTo("radar_frontend.bit");
        assertThat(image.design()).isEqualTo("radar_frontend");
    }

    @Test
    void aMissingFileIsRefused(@TempDir Path tmp) {
        assertThatThrownBy(() -> Bitstream.load(tmp.resolve("nothing.bit"))).isInstanceOf(ValidationError.class);
    }

    @Test
    void theSummaryReadsLikeALogLine() {
        assertThat(sample().bitstream().summary()).startsWith("radar_frontend full for xc7a100t");
    }
}
