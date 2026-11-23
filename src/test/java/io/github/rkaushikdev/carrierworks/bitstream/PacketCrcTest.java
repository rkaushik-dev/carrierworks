package io.github.rkaushikdev.carrierworks.bitstream;

import static io.github.rkaushikdev.carrierworks.bitstream.Packets.SYNC_WORD;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.type1;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.type2;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.wordsToBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

class PacketCrcTest {

    private static final int CMD = 4;
    private static final int IDCODE = 12;
    private static final int FDRI = 2;
    private static final int CRC = 0;

    /** A stream of register writes, with the crc the device would compute kept alongside. */
    private static final class Stream {
        private final List<Long> words = new ArrayList<>(List.of(0xFFFF_FFFFL, SYNC_WORD));
        private long crc;

        Stream write(int register, long word) {
            words.add(type1(2, register, 1));
            words.add(word);
            crc = register == CMD && word == 7 ? 0 : PacketCrc.next(crc, register, word);
            return this;
        }

        Stream frames(int count) {
            words.add(type1(2, FDRI, 0));
            words.add(type2(2, count));
            for (long word = 0; word < count; word++) {
                words.add(0xC0DE_0000L + word);
                crc = PacketCrc.next(crc, FDRI, 0xC0DE_0000L + word);
            }
            return this;
        }

        Stream check() {
            return check(crc);
        }

        Stream check(long value) {
            words.add(type1(2, CRC, 1));
            words.add(value);
            crc = 0;
            return this;
        }

        byte[] bytes() {
            return wordsToBytes(words);
        }
    }

    private static Stream stream() {
        return new Stream().write(CMD, 7).write(IDCODE, 0x03651093L).write(CMD, 1);
    }

    @Test
    void theShiftRegisterIsCrc32cFedLeastSignificantBitFirst() {
        long word = 0x1234_5678L;
        CRC32C reference = new CRC32C();
        reference.update(new byte[] {0x78, 0x56, 0x34, 0x12});
        long afterWord = ~reference.getValue() & 0xFFFF_FFFFL;
        for (int bit = 0; bit < 5; bit++) {
            afterWord = (afterWord & 1) != 0 ? (afterWord >>> 1) ^ 0x82F6_3B78L : afterWord >>> 1;
        }
        assertThat(PacketCrc.next(0xFFFF_FFFFL, 0, word)).isEqualTo(afterWord);
    }

    @Test
    void theRegisterAddressIsPartOfWhatIsChecked() {
        assertThat(PacketCrc.next(0, FDRI, 0xAB)).isNotEqualTo(PacketCrc.next(0, IDCODE, 0xAB));
        assertThat(PacketCrc.next(0, 1, 0xAB)).isEqualTo(PacketCrc.next(0, 33, 0xAB));
    }

    @Test
    void aStreamWhoseCheckMatchesPasses() {
        PacketCrc crc =
                PacketCrc.check(stream().frames(32).check().write(CMD, 5).bytes());
        assertThat(crc.passed()).isTrue();
        assertThat(crc.checked()).isEqualTo(1);
        assertThat(crc.describe()).isEqualTo("crc ok, 1 check");
    }

    @Test
    void aDamagedFrameWordIsCaught() {
        byte[] data = stream().frames(32).check().bytes();
        data[data.length - 40] ^= 0x10;
        PacketCrc crc = PacketCrc.check(data);
        assertThat(crc.passed()).isFalse();
        assertThat(crc.failed()).isEqualTo(1);
        assertThat(crc.describe()).startsWith("crc failed 1 of 1: image says 0x");
    }

    @Test
    void theResetCommandStartsTheCountAgain() {
        Stream stream = stream().frames(4).write(CMD, 7).frames(4).check();
        assertThat(PacketCrc.check(stream.bytes()).passed()).isTrue();
    }

    @Test
    void eachComparisonStartsTheCountAgain() {
        Stream stream = stream().frames(4).check().frames(4).check();
        PacketCrc crc = PacketCrc.check(stream.bytes());
        assertThat(crc.checked()).isEqualTo(2);
        assertThat(crc.passed()).isTrue();
        assertThat(crc.describe()).isEqualTo("crc ok, 2 checks");
    }

    @Test
    void onlyTheFailingChecksAreCounted() {
        PacketCrc crc =
                PacketCrc.check(stream().check().frames(2).check(0xDEAD_BEEFL).bytes());
        assertThat(crc.checked()).isEqualTo(2);
        assertThat(crc.failed()).isEqualTo(1);
        assertThat(crc.firstMismatch())
                .hasValueSatisfying(text -> assertThat(text).startsWith("image says 0xdeadbeef"));
    }

    @Test
    void anImageBuiltWithTheCrcOffChecksNothing() {
        PacketCrc crc = PacketCrc.check(stream().frames(8).bytes());
        assertThat(crc.checked()).isZero();
        assertThat(crc.passed()).isFalse();
        assertThat(crc.describe()).isEqualTo("crc not written");
    }

    @Test
    void readsAreNotPartOfTheCount() {
        Stream stream = stream();
        stream.words.add(type1(1, 7, 1));
        stream.words.add(0x1234L);
        assertThat(PacketCrc.check(stream.check().bytes()).passed()).isTrue();
    }

    @Test
    void anImageThatFailsItsOwnCheckIsRefusedAsItIsRead() {
        byte[] bitfile = BitFile.build(
                "radar_frontend",
                Images.KINTEX_PART,
                "2026/01/15",
                "02:00:00",
                stream().frames(16).check(0x0BAD_0BADL).write(CMD, 5).bytes());
        assertThatThrownBy(() -> Bitstream.read(bitfile, "radar_frontend.bit"))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("fails its own crc check");
    }

    @Test
    void anImageThatPassesItsCheckIsReadAndSaysSo() {
        byte[] bitfile = BitFile.build(
                "radar_frontend",
                Images.KINTEX_PART,
                "2026/01/15",
                "02:00:00",
                stream().frames(16).check().write(CMD, 5).bytes());
        Bitstream image = Bitstream.read(bitfile);
        assertThat(image.crc().passed()).isTrue();
        assertThat(Images.bitstream().crc().checked()).isZero();
    }

    @Test
    void theWalkStillRefusesAPacketThatRunsOffTheEnd() {
        byte[] data = wordsToBytes(0xFFFF_FFFFL, SYNC_WORD, type1(2, FDRI, 0), type2(2, 100), 1L);
        assertThatThrownBy(() -> PacketCrc.check(data))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("ran off the end");
    }
}
