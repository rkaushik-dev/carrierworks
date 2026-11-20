package io.github.rkaushikdev.carrierworks.bitstream;

import static io.github.rkaushikdev.carrierworks.bitstream.Binary.byteSwap;
import static io.github.rkaushikdev.carrierworks.bitstream.Binary.padTo;
import static io.github.rkaushikdev.carrierworks.bitstream.Binary.reverseBits;
import static io.github.rkaushikdev.carrierworks.bitstream.Binary.splitPages;
import static io.github.rkaushikdev.carrierworks.bitstream.Binary.toBin;
import static io.github.rkaushikdev.carrierworks.bitstream.Binary.toFlashImage;
import static io.github.rkaushikdev.carrierworks.bitstream.Binary.trimErased;
import static io.github.rkaushikdev.carrierworks.bitstream.Binary.wordSwap;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.SYNC_WORD;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.type1;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.wordsToBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BinaryTest {

    private static final byte[] PAYLOAD =
            wordsToBytes(0xFFFF_FFFFL, SYNC_WORD, type1(2, 12, 1), 0x03631093L, type1(2, 4, 1), 5);

    private static Bitstream image() {
        return Bitstream.read(BitFile.build("beamformer", "7a100tfgg484", "2025/05/09", "10:11:12", PAYLOAD));
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            out[index] = (byte) values[index];
        }
        return out;
    }

    @Test
    void bitReversalTurnsEachByteRound() {
        assertThat(reverseBits(bytes(0x01))).isEqualTo(bytes(0x80));
        assertThat(reverseBits(bytes(0xF0))).isEqualTo(bytes(0x0F));
        assertThat(reverseBits(bytes(0xAA))).isEqualTo(bytes(0x55));
    }

    @Test
    void bitReversalLeavesTheByteOrderAlone() {
        assertThat(reverseBits(bytes(0x01, 0x02))).isEqualTo(bytes(0x80, 0x40));
    }

    @Test
    void bitReversalIsItsOwnInverse() {
        assertThat(reverseBits(reverseBits(PAYLOAD))).isEqualTo(PAYLOAD);
    }

    @Test
    void byteSwappingTurnsPairsRound() {
        assertThat(byteSwap(bytes(1, 2, 3, 4))).isEqualTo(bytes(2, 1, 4, 3));
        assertThat(wordSwap(bytes(1, 2, 3, 4))).isEqualTo(bytes(4, 3, 2, 1));
    }

    @Test
    void byteSwappingIsItsOwnInverse() {
        assertThat(wordSwap(wordSwap(PAYLOAD))).isEqualTo(PAYLOAD);
    }

    @Test
    void aSwapThatDoesNotDivideTheImageIsRefused() {
        assertThatThrownBy(() -> wordSwap(bytes(1, 2, 3))).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> byteSwap(bytes(1, 2), 1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void theBinFormIsTheImageWithoutTheHeader() {
        assertThat(toBin(image())).isEqualTo(PAYLOAD);
    }

    @Test
    void theFlashFormIsBitReversedByDefault() {
        assertThat(toFlashImage(image())).isEqualTo(reverseBits(PAYLOAD));
    }

    @Test
    void theSwapCanBeTurnedOffForTheCarriersThatDoItThemselves() {
        assertThat(toFlashImage(image(), false)).isEqualTo(PAYLOAD);
    }

    @Test
    void paddingFillsWithTheErasedValue() {
        assertThat(padTo(bytes(1, 2), 5)).isEqualTo(bytes(1, 2, 0xFF, 0xFF, 0xFF));
        assertThat(padTo(bytes(1), 3, 0x00)).isEqualTo(bytes(1, 0, 0));
    }

    @Test
    void paddingToTheLengthItAlreadyIsChangesNothing() {
        assertThat(padTo(bytes(1, 2), 2)).isEqualTo(bytes(1, 2));
    }

    @Test
    void paddingDownIsRefusedBecauseItWouldBeATruncation() {
        ValidationError caught = catchThrowableOfType(ValidationError.class, () -> padTo(bytes(1, 2, 3), 2));
        assertThat(caught.details()).containsEntry("have", 3);
    }

    @ParameterizedTest
    @CsvSource({"-1, 0xFF", "4, 256", "4, -1"})
    void nonsensePaddingIsRefused(long length, String fill) {
        int value = Integer.decode(fill);
        assertThatThrownBy(() -> padTo(bytes(1), length, value)).isInstanceOf(ValidationError.class);
    }

    @Test
    void trimmingTakesTheErasedTailOff() {
        assertThat(trimErased(bytes(1, 2, 0xFF, 0xFF))).isEqualTo(bytes(1, 2));
        assertThat(trimErased(bytes(0xFF, 0xFF))).isEmpty();
        assertThat(trimErased(bytes(1, 2))).isEqualTo(bytes(1, 2));
    }

    @Test
    void trimmingLeavesErasedBytesInTheMiddleAlone() {
        assertThat(trimErased(bytes(1, 0xFF, 2, 0xFF))).isEqualTo(bytes(1, 0xFF, 2));
    }

    @Test
    void paddingAndTrimmingRoundTrip() {
        assertThat(trimErased(padTo(PAYLOAD, PAYLOAD.length + 64))).isEqualTo(PAYLOAD);
    }

    @Test
    void pagesComeOutTheRightSizeWithAShortLastOne() {
        byte[] image = new byte[700];
        Arrays.fill(image, (byte) 1);
        List<byte[]> pages = splitPages(image, 256);
        assertThat(pages).extracting(page -> page.length).containsExactly(256, 256, 188);
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        pages.forEach(joined::writeBytes);
        assertThat(joined.toByteArray()).isEqualTo(image);
    }

    @Test
    void splittingNothingGivesNoPages() {
        assertThat(splitPages(new byte[0], 256)).isEmpty();
    }

    @Test
    void aPageSizeOfNothingIsRefused() {
        assertThatThrownBy(() -> splitPages(bytes(1), 0)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aFlashImageRoundTripsThroughPaddingAndSwapping() {
        byte[] flash = toFlashImage(image());
        byte[] padded = padTo(flash, flash.length + 100);
        assertThat(reverseBits(trimErased(padded))).isEqualTo(PAYLOAD);
    }
}
