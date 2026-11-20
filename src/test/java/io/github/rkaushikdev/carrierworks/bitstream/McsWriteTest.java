package io.github.rkaushikdev.carrierworks.bitstream;

import static io.github.rkaushikdev.carrierworks.bitstream.Mcs.DATA;
import static io.github.rkaushikdev.carrierworks.bitstream.Mcs.END_OF_FILE;
import static io.github.rkaushikdev.carrierworks.bitstream.Mcs.EXTENDED_LINEAR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class McsWriteTest {

    private static List<String> lines(String text) {
        return text.strip().lines().toList();
    }

    private static byte[] counting(int length) {
        byte[] data = new byte[length];
        for (int index = 0; index < length; index++) {
            data[index] = (byte) index;
        }
        return data;
    }

    private static byte[] filled(int length, int value) {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte) value);
        return data;
    }

    private static int byteSum(String record) {
        int sum = 0;
        for (byte value : HexFormat.of().parseHex(record.substring(1))) {
            sum += Byte.toUnsignedInt(value);
        }
        return sum;
    }

    private static int field(String line, int from, int to) {
        return Integer.parseInt(line.substring(from, to), 16);
    }

    @Test
    void theEndOfFileRecordIsTheOneEverybodyKnows() {
        assertThat(Mcs.hexRecord(0, END_OF_FILE, new byte[0])).isEqualTo(":00000001FF");
    }

    @Test
    void aDataRecordChecksumsTheWayTheFormatSays() {
        String record = Mcs.hexRecord(0x0100, DATA, new byte[] {0x01, 0x02, 0x03, 0x04});
        assertThat(record).isEqualTo(":0401000001020304F1");
        assertThat(byteSum(record) % 256).isZero();
    }

    @Test
    void anExtendedLinearRecordCarriesTheUpperAddress() {
        String record = Mcs.hexRecord(0, EXTENDED_LINEAR, new byte[] {0x00, 0x0F});
        assertThat(record).isEqualTo(":02000004000FEB");
    }

    @ParameterizedTest
    @CsvSource({"0x10000, 0x00, 0", "0, 0x100, 0", "0, 0x00, 300"})
    void recordsThatDoNotFitAreRefused(String address, String recordType, int payload) {
        int where = Integer.decode(address);
        int type = Integer.decode(recordType);
        assertThatThrownBy(() -> Mcs.hexRecord(where, type, new byte[payload])).isInstanceOf(ValidationError.class);
    }

    @Test
    void aSmallImageWritesASegmentADataRecordAndAnEnd() {
        assertThat(lines(Mcs.write(counting(8))))
                .containsExactly(":020000040000FA", ":080000000001020304050607DC", ":00000001FF");
    }

    @Test
    void theFirstRecordSetsTheSegment() {
        assertThat(lines(Mcs.write(counting(8))).getFirst()).startsWith(":02000004");
    }

    @Test
    void theLastRecordEndsTheFile() {
        assertThat(lines(Mcs.write(counting(8))).getLast()).isEqualTo(":00000001FF");
    }

    @Test
    void recordsAreTheLengthThatWasAskedFor() {
        List<String> all = lines(Mcs.write(counting(64), 0, 32));
        assertThat(all.subList(1, all.size() - 1).stream().map(line -> field(line, 1, 3)))
                .containsExactly(32, 32);
    }

    @Test
    void aShortLastRecordCarriesWhatIsLeft() {
        List<String> all = lines(Mcs.write(counting(40), 0, 16));
        assertThat(all.subList(1, all.size() - 1).stream().map(line -> field(line, 1, 3)))
                .containsExactly(16, 16, 8);
    }

    @Test
    void aNewSegmentRecordAppearsEverySixtyFourKilobytes() {
        String text = Mcs.write(filled(0x10000 + 32, 0xA5));
        List<String> segments = lines(text).stream()
                .filter(line -> line.startsWith(":02000004"))
                .toList();
        assertThat(segments).hasSize(2);
        assertThat(segments.get(1)).isEqualTo(":020000040001F9");
    }

    @Test
    void noDataRecordStraddlesASegmentBoundary() {
        String text = Mcs.write(filled(0x10000 + 32, 0xA5), 0, 32);
        for (String line : lines(text)) {
            if (!line.substring(7, 9).equals("00")) {
                continue;
            }
            int address = field(line, 3, 7);
            assertThat(address + field(line, 1, 3)).isLessThanOrEqualTo(0x10000);
        }
    }

    @Test
    void anImageCanStartPartWayUpThePart() {
        String text = Mcs.write(new byte[] {1, 2}, 0x400000);
        assertThat(lines(text).getFirst()).isEqualTo(":020000040040BA");
    }

    @Test
    void aStartAddressThatIsNotOnASegmentBoundaryIsRefused() {
        assertThatThrownBy(() -> Mcs.write(new byte[] {1}, 0x1234)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aNegativeStartAddressIsRefused() {
        assertThatThrownBy(() -> Mcs.write(new byte[] {1}, -0x10000)).isInstanceOf(ValidationError.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 256})
    void aNonsenseRecordLengthIsRefused(int recordBytes) {
        assertThatThrownBy(() -> Mcs.write(new byte[] {1}, 0, recordBytes)).isInstanceOf(ValidationError.class);
    }

    @Test
    void anEmptyImageStillWritesATerminatedFile() {
        assertThat(lines(Mcs.write(new byte[0]))).containsExactly(":00000001FF");
    }

    @Test
    void everyLineChecksums() {
        for (String line : lines(Mcs.write(counting(256 * 3)))) {
            assertThat(byteSum(line) % 256).isZero();
        }
    }
}
