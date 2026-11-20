package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A run of bits of a known length, held least significant bit first.
 *
 * <p>A scan chain shifts least significant bit first, a person writes bit strings most significant
 * on the left, and a byte buffer for a cable driver packs eight bits at a time from the low end.
 * Three orders for the same thing, and every one of them is correct in its own context. What is not
 * correct is losing the length: a vector of six zeros and a vector of eight zeros are different
 * scans and an integer cannot tell them apart.
 *
 * <p>So the length is carried, the value is an unsigned integer, and every conversion says which
 * end it starts from in its name. Bit zero is always the one that goes out on the wire first.
 *
 * @param value the bits as an unsigned integer, bit zero first out
 * @param length how many bits there are, leading zeros included
 */
public record BitVector(BigInteger value, int length) {

    public BitVector {
        Objects.requireNonNull(value, "value");
        if (length < 0) {
            throw new ValidationError("a vector cannot be shorter than nothing", "length");
        }
        if (value.signum() < 0) {
            throw new ValidationError("a vector is not negative", "value");
        }
        if (length > 0 && value.bitLength() > length) {
            throw new ValidationError("value does not fit in that many bits", "value", Details.of("length", length));
        }
        if (length == 0 && value.signum() != 0) {
            throw new ValidationError("an empty vector holds nothing", "value");
        }
    }

    /** A vector whose value fits in a {@code long}, which is nearly every register on a chain. */
    public BitVector(long value, int length) {
        this(BigInteger.valueOf(value), length);
    }

    /** {@code length} zeros. */
    public static BitVector zeros(int length) {
        return new BitVector(BigInteger.ZERO, length);
    }

    /** {@code length} ones, which is bypass on every device and what a discovery scan shifts in. */
    public static BitVector ones(int length) {
        return new BitVector(length > 0 ? mask(length) : BigInteger.ZERO, length);
    }

    /**
     * From a written string, most significant bit on the left.
     *
     * <p>Spaces and underscores are grouping and are ignored, so {@code 1010_0011} and {@code 1010
     * 0011} both read as eight bits. An empty string is the empty vector.
     */
    public static BitVector parse(String text) {
        String cleaned = text.strip().replace(" ", "").replace("_", "");
        if (!cleaned.chars().allMatch(ch -> ch == '0' || ch == '1')) {
            throw new ValidationError("a bit string is ones and zeros", "bits", Details.of("given", text));
        }
        if (cleaned.isEmpty()) {
            return new BitVector(BigInteger.ZERO, 0);
        }
        return new BitVector(new BigInteger(cleaned, 2), cleaned.length());
    }

    /** From bits in clock order, the first one out first. Each has to be a zero or a one. */
    public static BitVector fromBits(Iterable<Integer> bits) {
        PackedBits packed = new PackedBits();
        for (Integer bit : bits) {
            if (bit == null || (bit != 0 && bit != 1)) {
                throw new ValidationError("a vector is made of ones and zeros", "bits");
            }
            packed.append(bit == 1);
        }
        return packed.toVector();
    }

    /** Lay vectors end to end in shift order, first one out first. */
    public static BitVector join(Iterable<BitVector> parts) {
        BigInteger value = BigInteger.ZERO;
        int length = 0;
        for (BitVector part : parts) {
            value = value.or(part.value.shiftLeft(length));
            length += part.length;
        }
        return new BitVector(value, length);
    }

    /** From a whole driver buffer, first byte lowest, bit zero of it first. */
    public static BitVector fromBytes(byte[] data) {
        return fromBytes(data, data.length * 8);
    }

    /**
     * From the first {@code length} bits of a driver buffer, first byte lowest, bit zero of it first.
     * Bits past the length are dropped.
     */
    public static BitVector fromBytes(byte[] data, int length) {
        int available = data.length * 8;
        if (length > available) {
            throw new ValidationError("not enough bytes for that many bits", "length");
        }
        BigInteger value = littleEndian(data);
        if (length < available) {
            value = value.and(mask(length));
        }
        return new BitVector(value, length);
    }

    /** Out to a driver buffer, first byte lowest, padded with zeros up to a whole number of bytes. */
    public byte[] toBytes() {
        byte[] bigEndian = value.toByteArray();
        byte[] out = new byte[(length + 7) / 8];
        for (int index = 0; index < out.length && index < bigEndian.length; index++) {
            out[index] = bigEndian[bigEndian.length - 1 - index];
        }
        return out;
    }

    /**
     * The value as a {@code long}, for registers that are a word or two wide.
     *
     * @throws ArithmeticException when the value does not fit in a signed {@code long}
     */
    public long toLong() {
        return value.longValueExact();
    }

    /** One bit, counted from the end that shifts first. */
    public int bit(int index) {
        if (index < 0 || index >= length) {
            throw new ValidationError("bit index is outside the vector", "index", Details.of("given", index));
        }
        return value.testBit(index) ? 1 : 0;
    }

    /** A run of {@code length} bits starting {@code start} bits from the shifting end. */
    public BitVector slice(int start, int length) {
        if (start < 0 || length < 0 || start + length > this.length) {
            throw new ValidationError(
                    "slice is outside the vector",
                    "slice",
                    Details.of("start", start, "length", length, "have", this.length));
        }
        if (length == 0) {
            return new BitVector(BigInteger.ZERO, 0);
        }
        return new BitVector(value.shiftRight(start).and(mask(length)), length);
    }

    /** Every bit in clock order, which is what a driver loop wants. */
    public List<Integer> bits() {
        List<Integer> out = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            out.add(value.testBit(index) ? 1 : 0);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Cut into consecutive runs from the shifting end.
     *
     * <p>The widths have to add up to the whole vector. A chain register that does not add up is a
     * bug in whoever built it, not something to pad.
     */
    public List<BitVector> split(List<Integer> widths) {
        List<BitVector> pieces = new ArrayList<>(widths.size());
        int offset = 0;
        for (int width : widths) {
            pieces.add(slice(offset, width));
            offset += width;
        }
        if (offset != length) {
            throw new ValidationError(
                    "widths do not add up to the vector", "widths", Details.of("covered", offset, "length", length));
        }
        return List.copyOf(pieces);
    }

    /** The same bits, other way round, for the registers that want it. */
    public BitVector reversed() {
        PackedBits packed = new PackedBits();
        for (int index = length - 1; index >= 0; index--) {
            packed.append(value.testBit(index));
        }
        return packed.toVector();
    }

    /** Split into pieces of {@code size} bits from the shifting end, the last one short. */
    public List<BitVector> chunks(int size) {
        if (size <= 0) {
            throw new ValidationError("chunk size is positive", "size", Details.of("given", size));
        }
        List<BitVector> pieces = new ArrayList<>();
        for (int offset = 0; offset < length; offset += size) {
            pieces.add(slice(offset, Math.min(size, length - offset)));
        }
        return List.copyOf(pieces);
    }

    /** Concatenation in shift order: this vector goes out first and {@code other} after it. */
    public BitVector concat(BitVector other) {
        return new BitVector(value.or(other.value.shiftLeft(length)), length + other.length);
    }

    /** Written out most significant bit first, the way people read them; empty for no bits. */
    @Override
    public String toString() {
        if (length == 0) {
            return "";
        }
        String digits = value.toString(2);
        return "0".repeat(length - digits.length()) + digits;
    }

    /** The form a log line or a debugger shows: {@code BitVector('0011')}. */
    public String repr() {
        return "BitVector('" + this + "')";
    }

    private static BigInteger mask(int length) {
        return BigInteger.ONE.shiftLeft(length).subtract(BigInteger.ONE);
    }

    private static BigInteger littleEndian(byte[] data) {
        byte[] bigEndian = new byte[data.length];
        for (int index = 0; index < data.length; index++) {
            bigEndian[data.length - 1 - index] = data[index];
        }
        return new BigInteger(1, bigEndian);
    }

    /**
     * Bits gathered one clock at a time into a little-endian buffer, so a vector of any length is
     * built in a single pass rather than by widening an integer once per bit.
     */
    static final class PackedBits {

        private byte[] buffer = new byte[16];
        private int length;

        void append(boolean bit) {
            if (length == buffer.length * 8) {
                buffer = Arrays.copyOf(buffer, buffer.length * 2);
            }
            if (bit) {
                buffer[length >>> 3] |= (byte) (1 << (length & 7));
            }
            length++;
        }

        BitVector toVector() {
            return fromBytes(Arrays.copyOf(buffer, (length + 7) / 8), length);
        }
    }
}
