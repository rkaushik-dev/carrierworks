package io.github.rkaushikdev.carrierworks.bitstream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class McsReadTest {

    private static final byte[] IMAGE = counting(256 * 3);

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

    private static List<String> lines(String text) {
        return text.strip().lines().toList();
    }

    private static List<String> allButLast(List<String> lines) {
        return lines.subList(0, lines.size() - 1);
    }

    private static String joined(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return String.join("\n", all);
    }

    @Test
    void aWrittenFileReadsBackAsOneRegion() {
        List<HexRegion> regions = Mcs.read(Mcs.write(IMAGE));
        assertThat(regions).hasSize(1);
        assertThat(regions.getFirst().start()).isZero();
        assertThat(regions.getFirst().data()).isEqualTo(IMAGE);
    }

    @Test
    void aFileThatStartsPartWayUpKeepsItsAddress() {
        List<HexRegion> regions = Mcs.read(Mcs.write(IMAGE, 0x400000));
        assertThat(regions.getFirst().start()).isEqualTo(0x400000);
        assertThat(regions.getFirst().end()).isEqualTo(0x400000 + IMAGE.length);
        assertThat(regions.getFirst().length()).isEqualTo(IMAGE.length);
    }

    @Test
    void aRegionDescribesItselfInHex() {
        HexRegion region = Mcs.read(Mcs.write(new byte[] {1, 2}, 0x10000)).getFirst();
        assertThat(region.describe()).isEqualTo("0x00010000..0x00010002 (2 bytes)");
    }

    @Test
    void recordsAcrossASegmentBoundaryStayOneRegion() {
        byte[] image = filled(0x10000 + 64, 0xA5);
        List<HexRegion> regions = Mcs.read(Mcs.write(image));
        assertThat(regions).hasSize(1);
        assertThat(regions.getFirst().data()).isEqualTo(image);
    }

    @Test
    void twoImagesInOneFileComeBackAsTwoRegions() {
        List<String> golden = allButLast(lines(Mcs.write(filled(32, 0x11))));
        List<String> update = lines(Mcs.write(filled(32, 0x22), 0x400000));
        List<HexRegion> regions = Mcs.read(joined(golden, update));
        assertThat(regions).extracting(HexRegion::start).containsExactly(0L, 0x400000L);
        assertThat(regions.get(1).data()).isEqualTo(filled(32, 0x22));
    }

    @Test
    void flatteningFillsTheHoleBetweenRegions() {
        List<String> golden = allButLast(lines(Mcs.write(filled(32, 0x11))));
        List<String> update = lines(Mcs.write(filled(32, 0x22), 0x10000));
        HexRegion flat = Mcs.flatten(Mcs.read(joined(golden, update)));
        assertThat(flat.start()).isZero();
        assertThat(flat.length()).isEqualTo(0x10000 + 32);
        assertThat(Arrays.copyOfRange(flat.data(), 32, 0x10000)).isEqualTo(filled(0x10000 - 32, 0xFF));
    }

    @Test
    void flatteningReportsWhereTheImageActuallyStarts() {
        HexRegion flat = Mcs.flatten(Mcs.read(Mcs.write(new byte[] {1, 2}, 0x400000)));
        assertThat(flat.start()).isEqualTo(0x400000);
        assertThat(flat.data()).isEqualTo(new byte[] {1, 2});
    }

    @Test
    void flatteningNothingGivesNothing() {
        assertThat(Mcs.flatten(List.of())).isEqualTo(new HexRegion(0, new byte[0]));
    }

    @Test
    void overlappingRegionsAreRefused() {
        List<HexRegion> regions = List.of(new HexRegion(0, new byte[] {1, 2, 3}), new HexRegion(2, new byte[] {4}));
        assertThatThrownBy(() -> Mcs.flatten(regions)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aBadFillIsRefused() {
        List<HexRegion> regions = List.of(new HexRegion(0, new byte[] {1}));
        assertThatThrownBy(() -> Mcs.flatten(regions, 999)).isInstanceOf(ValidationError.class);
    }

    @Test
    void blankLinesAreIgnored() {
        String text = String.join("\n\n", lines(Mcs.write(new byte[] {1, 2})));
        assertThat(Mcs.read(text).getFirst().data()).isEqualTo(new byte[] {1, 2});
    }

    @Test
    void aFileWithoutAnEndRecordIsRefused() {
        String text = String.join("\n", allButLast(lines(Mcs.write(IMAGE))));
        assertThatThrownBy(() -> Mcs.read(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void recordsAfterTheEndRecordAreRefused() {
        String text = Mcs.write(new byte[] {1}) + Mcs.hexRecord(0, 0, new byte[] {2}) + "\n";
        assertThatThrownBy(() -> Mcs.read(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aLineThatIsNotARecordIsRefused() {
        assertThatThrownBy(() -> Mcs.read("hello\n:00000001FF\n")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aRecordThatIsNotHexIsRefused() {
        assertThatThrownBy(() -> Mcs.read(":zz00000102\n:00000001FF\n")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aRecordThatIsTooShortIsRefused() {
        assertThatThrownBy(() -> Mcs.read(":0000\n:00000001FF\n")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aBrokenChecksumIsRefused() {
        List<String> good = lines(Mcs.write(new byte[] {1, 2}));
        String broken = good.get(1).substring(0, good.get(1).length() - 2) + "00";
        ValidationError caught = catchThrowableOfType(
                ValidationError.class, () -> Mcs.read(String.join("\n", good.get(0), broken, good.get(2))));
        assertThat(caught.details()).containsEntry("line", 2);
    }

    @Test
    void aRecordThatLiesAboutItsLengthIsRefused() {
        // count says four, payload holds two, and the checksum is made to agree
        byte[] body = {0x04, 0x00, 0x00, 0x00, 0x01, 0x02};
        int sum = 0;
        for (byte value : body) {
            sum += Byte.toUnsignedInt(value);
        }
        byte[] record = Arrays.copyOf(body, body.length + 1);
        record[body.length] = (byte) -sum;
        String line = ":" + HexFormat.of().withUpperCase().formatHex(record);
        assertThatThrownBy(() -> Mcs.read(line + "\n:00000001FF\n")).isInstanceOf(ValidationError.class);
    }

    @Test
    void anUnsupportedRecordTypeIsRefused() {
        String line = Mcs.hexRecord(0, 0x03, new byte[4]);
        assertThatThrownBy(() -> Mcs.read(line + "\n:00000001FF\n")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aSegmentRecordOfTheWrongLengthIsRefused() {
        String line = Mcs.hexRecord(0, 0x04, new byte[1]);
        assertThatThrownBy(() -> Mcs.read(line + "\n:00000001FF\n")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aBigImageRoundTripsByteForByte() {
        byte[] image = counting(256 * 400);
        HexRegion flat = Mcs.flatten(Mcs.read(Mcs.write(image, 0, 32)));
        assertThat(flat.start()).isZero();
        assertThat(flat.data()).isEqualTo(image);
    }
}
