package io.github.rkaushikdev.carrierworks.bitstream;

import static io.github.rkaushikdev.carrierworks.bitstream.Packets.BUS_WIDTH_PATTERN;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.SYNC_WORD;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.commandNames;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.findSync;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.idcodeFrom;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.parse;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.registerName;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.type1;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.type2;
import static io.github.rkaushikdev.carrierworks.bitstream.Packets.wordsToBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PacketsTest {

    private static final long ARTIX = 0x03631093L;

    private static byte[] stream(long... words) {
        List<Long> all = new ArrayList<>(List.of(0xFFFF_FFFFL, 0xFFFF_FFFFL));
        all.addAll(BUS_WIDTH_PATTERN);
        all.add(0xFFFF_FFFFL);
        all.add(SYNC_WORD);
        LongStream.of(words).forEach(all::add);
        return wordsToBytes(all);
    }

    private static byte[] simpleImage() {
        return simpleImage(ARTIX);
    }

    private static byte[] simpleImage(long idcode) {
        return stream(
                type1(2, 4, 1),
                7, // rcrc
                type1(2, 12, 1),
                idcode,
                type1(2, 4, 1),
                1, // wcfg
                type1(2, 2, 0),
                type2(2, 4),
                0xAAAA0000L,
                0xAAAA0001L,
                0xAAAA0002L,
                0xAAAA0003L,
                type1(2, 4, 1),
                5, // start
                type1(2, 4, 1),
                13 // desync
                );
    }

    @Test
    void theSyncWordIsFoundPastTheDummies() {
        assertThat(findSync(simpleImage())).isEqualTo(6 * 4);
    }

    @Test
    void anImageWithoutASyncWordIsRefused() {
        assertThatThrownBy(() -> findSync(new byte[64])).isInstanceOf(ValidationError.class);
    }

    @Test
    void thePacketsComeOutInOrder() {
        assertThat(parse(simpleImage()).stream().map(ConfigPacket::registerName))
                .containsExactly("cmd", "idcode", "cmd", "fdri", "fdri", "cmd", "cmd");
    }

    @Test
    void aTypeTwoPacketInheritsTheRegisterBeforeIt() {
        ConfigPacket frame = parse(simpleImage()).get(4);
        assertThat(frame.kind()).isEqualTo(2);
        assertThat(frame.registerName()).isEqualTo("fdri");
        assertThat(frame.wordCount()).isEqualTo(4);
    }

    @Test
    void theIdcodeWriteIsTheImageSOwnStatementOfItsDevice() {
        assertThat(idcodeFrom(parse(simpleImage()))).contains(ARTIX);
        assertThat(idcodeFrom(parse(simpleImage(0x03651093L)))).contains(0x03651093L);
    }

    @Test
    void anImageThatNeverWritesAnIdcodeSaysSo() {
        byte[] data = stream(type1(2, 4, 1), 5);
        assertThat(idcodeFrom(parse(data))).isEmpty();
    }

    @Test
    void theCommandsReadBackByName() {
        assertThat(commandNames(parse(simpleImage()))).containsExactly("rcrc", "wcfg", "start", "desync");
    }

    @Test
    void anUnknownCommandKeepsItsNumber() {
        byte[] data = stream(type1(2, 4, 1), 30);
        assertThat(commandNames(parse(data))).containsExactly("command-30");
    }

    @Test
    void registersHaveNamesAndUnknownOnesDoNotPretend() {
        assertThat(registerName(12)).isEqualTo("idcode");
        assertThat(registerName(16)).isEqualTo("wbstar");
        assertThat(registerName(300)).isEqualTo("register-300");
    }

    @Test
    void aReadPacketIsNotAWrite() {
        ConfigPacket packet = parse(stream(type1(1, 7, 1), 0)).getFirst();
        assertThat(packet.opcode()).isEqualTo(Opcode.READ);
        assertThat(packet.isWrite()).isFalse();
    }

    @Test
    void aNopPacketCarriesNothing() {
        ConfigPacket packet = parse(stream(type1(0, 0, 0))).getFirst();
        assertThat(packet.opcode()).isEqualTo(Opcode.NOP);
        assertThat(packet.wordCount()).isZero();
        assertThat(packet.describe()).isEqualTo("type1 nop crc x0");
    }

    @Test
    void theStreamStopsAtAPadWord() {
        byte[] data = stream(type1(2, 4, 1), 5, 0xFFFF_FFFFL, type1(2, 4, 1), 13);
        assertThat(commandNames(parse(data))).containsExactly("start");
    }

    @Test
    void aPacketThatRunsOffTheEndIsRefused() {
        byte[] data = stream(type1(2, 2, 8), 1, 2);
        assertThatThrownBy(() -> parse(data)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aPacketTypeNobodyKnowsIsRefused() {
        byte[] data = stream(0b111L << 29);
        assertThatThrownBy(() -> parse(data)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aLongFrameWriteKeepsItsCountAndDropsItsPayload() {
        List<Long> words = new ArrayList<>(List.of(SYNC_WORD, type1(2, 2, 0), type2(2, 200)));
        LongStream.range(0, 200).forEach(words::add);
        ConfigPacket frame = parse(wordsToBytes(words), 100).get(1);
        assertThat(frame.wordCount()).isZero();
        assertThat(frame.describe()).isEqualTo("type2 write fdri x0");
    }

    @ParameterizedTest
    @CsvSource({"4, 4, 1", "2, 0x4000, 1", "2, 4, 0x800"})
    void aTypeOneHeaderThatDoesNotFitIsRefused(int opcode, String register, String count) {
        int address = Integer.decode(register);
        int words = Integer.decode(count);
        assertThatThrownBy(() -> type1(opcode, address, words)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aTypeTwoHeaderThatDoesNotFitIsRefused() {
        assertThatThrownBy(() -> type2(2, 0x8000000)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> type2(9, 1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aWordThatIsNotAWordIsRefused() {
        assertThatThrownBy(() -> wordsToBytes(0x1_0000_0000L)).isInstanceOf(ValidationError.class);
    }
}
