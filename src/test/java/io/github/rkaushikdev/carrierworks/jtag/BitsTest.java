package io.github.rkaushikdev.carrierworks.jtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.ValidationError;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BitsTest {

    private static BitVector bits(String text) {
        return BitVector.parse(text);
    }

    private static List<String> texts(List<BitVector> pieces) {
        return pieces.stream().map(BitVector::toString).toList();
    }

    @Test
    void lengthIsCarriedSoPaddingSurvives() {
        assertThat(BitVector.zeros(6).length()).isEqualTo(6);
        assertThat(BitVector.zeros(8).length()).isEqualTo(8);
        assertThat(BitVector.zeros(6)).isNotEqualTo(BitVector.zeros(8));
    }

    @Test
    void onesFillsTheWholeWidth() {
        assertThat(BitVector.ones(6).value()).isEqualTo(BigInteger.valueOf(0b111111));
        assertThat(BitVector.ones(0)).isEqualTo(new BitVector(0, 0));
    }

    @Test
    void aValueWiderThanItsLengthIsRefused() {
        assertThatThrownBy(() -> new BitVector(0b1000, 3)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new BitVector(1, 0)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new BitVector(-1, 4)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new BitVector(0, -1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void parsingReadsTheLeftHandBitAsMostSignificant() {
        assertThat(bits("1010").value()).isEqualTo(BigInteger.valueOf(0b1010));
        assertThat(bits("0001").length()).isEqualTo(4);
        assertThat(bits("").length()).isZero();
    }

    @Test
    void parsingToleratesGrouping() {
        assertThat(bits("1010_0011")).isEqualTo(bits("10100011"));
        assertThat(bits(" 11 00 ")).isEqualTo(bits("1100"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1012", "abc", "0x0f"})
    void parsingJunkIsRefused(String text) {
        assertThatThrownBy(() -> BitVector.parse(text)).isInstanceOf(ValidationError.class);
    }

    @Test
    void printingPutsTheMostSignificantBitOnTheLeft() {
        assertThat(bits("0011")).hasToString("0011");
        assertThat(new BitVector(0b1, 4)).hasToString("0001");
        assertThat(new BitVector(0, 0)).hasToString("");
        assertThat(bits("0011").repr()).isEqualTo("BitVector('0011')");
    }

    @Test
    void bitZeroIsTheOneThatShiftsFirst() {
        BitVector vector = bits("1000");
        assertThat(vector.bit(0)).isZero();
        assertThat(vector.bit(3)).isEqualTo(1);
    }

    @Test
    void readingOffTheEndIsRefused() {
        assertThatThrownBy(() -> bits("1010").bit(4)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> bits("1010").bit(-1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void bytesGoOutLittleEndFirst() {
        assertThat(new BitVector(0x0201, 16).toBytes()).containsExactly(0x01, 0x02);
        assertThat(bits("1").toBytes()).containsExactly(0x01);
    }

    @Test
    void bytesComeBackTheSameWay() {
        assertThat(BitVector.fromBytes(new byte[] {0x01, 0x02})).isEqualTo(new BitVector(0x0201, 16));
        assertThat(BitVector.fromBytes(new byte[] {(byte) 0xFF}, 6)).isEqualTo(new BitVector(0b111111, 6));
    }

    @Test
    void askingForMoreBitsThanTheBufferHoldsIsRefused() {
        assertThatThrownBy(() -> BitVector.fromBytes(new byte[] {(byte) 0xFF}, 9))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void bytesRoundTripWhenTheLengthIsKept() {
        BitVector written = bits("101100111");
        assertThat(BitVector.fromBytes(written.toBytes(), written.length())).isEqualTo(written);
    }

    @Test
    void concatenationPutsTheFirstOperandOutFirst() {
        BitVector head = bits("11");
        BitVector tail = bits("00");
        BitVector joined = head.concat(tail);
        assertThat(joined.length()).isEqualTo(4);
        assertThat(joined.bit(0)).isEqualTo(1);
        assertThat(joined.bit(3)).isZero();
        assertThat(joined).hasToString("0011");
    }

    @Test
    void concatenatingNothingChangesNothing() {
        BitVector vector = bits("1011");
        assertThat(vector.concat(new BitVector(0, 0))).isEqualTo(vector);
        assertThat(new BitVector(0, 0).concat(vector)).isEqualTo(vector);
    }

    @Test
    void slicingCountsFromTheShiftingEnd() {
        BitVector vector = bits("11110000");
        assertThat(vector.slice(0, 4)).hasToString("0000");
        assertThat(vector.slice(4, 4)).hasToString("1111");
        assertThat(vector.slice(2, 0)).isEqualTo(new BitVector(0, 0));
    }

    @Test
    void slicingOffTheEndIsRefused() {
        assertThatThrownBy(() -> bits("1010").slice(2, 3)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> bits("1010").slice(-1, 2)).isInstanceOf(ValidationError.class);
    }

    @Test
    void reversingTurnsTheVectorRound() {
        assertThat(bits("1000").reversed()).hasToString("0001");
        assertThat(bits("1010").reversed().reversed()).isEqualTo(bits("1010"));
        assertThat(new BitVector(0, 0).reversed()).isEqualTo(new BitVector(0, 0));
    }

    @Test
    void chunkingStartsAtTheShiftingEndAndLeavesTheShortPieceLast() {
        List<BitVector> pieces = bits("110100101").chunks(4);
        assertThat(texts(pieces)).containsExactly("0101", "1010", "1");
        assertThat(pieces.stream().mapToInt(BitVector::length).sum()).isEqualTo(9);
    }

    @Test
    void chunkingAVectorShorterThanTheChunkGivesOnePiece() {
        assertThat(bits("101").chunks(8)).hasSize(1);
    }

    @Test
    void aChunkSizeOfNothingIsRefused() {
        assertThatThrownBy(() -> bits("1010").chunks(0)).isInstanceOf(ValidationError.class);
    }

    @Test
    void anEmptyVectorChunksToNothing() {
        assertThat(new BitVector(0, 0).chunks(4)).isEmpty();
    }

    @Test
    void bitsComeBackInClockOrder() {
        assertThat(bits("1100").bits()).containsExactly(0, 0, 1, 1);
        assertThat(new BitVector(0, 0).bits()).isEmpty();
    }

    @Test
    void buildingFromClockOrderIsTheOtherDirection() {
        assertThat(BitVector.fromBits(List.of(0, 0, 1, 1))).isEqualTo(bits("1100"));
        assertThat(BitVector.fromBits(List.of())).isEqualTo(new BitVector(0, 0));
    }

    @Test
    void bitsRoundTripThroughClockOrder() {
        BitVector vector = bits("101100111");
        assertThat(BitVector.fromBits(vector.bits())).isEqualTo(vector);
    }

    @Test
    void buildingFromSomethingThatIsNotABitIsRefused() {
        assertThatThrownBy(() -> BitVector.fromBits(List.of(0, 1, 2))).isInstanceOf(ValidationError.class);
    }

    @Test
    void joiningLaysVectorsEndToEndInShiftOrder() {
        BitVector joined = BitVector.join(List.of(bits("11"), bits("00"), bits("1")));
        assertThat(joined).hasToString("10011");
        assertThat(joined.length()).isEqualTo(5);
    }

    @Test
    void joiningNothingGivesAnEmptyVector() {
        assertThat(BitVector.join(List.of())).isEqualTo(new BitVector(0, 0));
    }

    @Test
    void joiningAgreesWithAdding() {
        List<BitVector> parts = List.of(bits("110"), bits("0011"), bits("1"));
        BitVector added = parts.get(0).concat(parts.get(1)).concat(parts.get(2));
        assertThat(BitVector.join(parts)).isEqualTo(added);
    }

    @Test
    void splittingCutsFromTheShiftingEnd() {
        List<BitVector> pieces = bits("10011").split(List.of(2, 2, 1));
        assertThat(texts(pieces)).containsExactly("11", "00", "1");
    }

    @Test
    void splittingHasToAccountForEveryBit() {
        assertThatThrownBy(() -> bits("10011").split(List.of(2, 2))).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> bits("10011").split(List.of(2, 2, 2))).isInstanceOf(ValidationError.class);
    }

    @Test
    void splittingAndJoiningAreInverses() {
        BitVector vector = bits("110100101");
        List<Integer> widths = List.of(3, 4, 2);
        assertThat(BitVector.join(vector.split(widths))).isEqualTo(vector);
    }
}
