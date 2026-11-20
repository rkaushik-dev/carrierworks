package io.github.rkaushikdev.carrierworks.bitstream;

import java.util.Arrays;
import java.util.Locale;

/**
 * A run of bytes that were contiguous in a hex file, and the address the first one loads at.
 *
 * @param start the address of the first byte
 * @param data the bytes
 */
public record HexRegion(long start, byte[] data) {

    public HexRegion {
        data = data.clone();
    }

    /** The bytes; a copy, so the region cannot be changed through it. */
    @Override
    public byte[] data() {
        return data.clone();
    }

    /** One past the last byte, the way a slice reads. */
    public long end() {
        return start + data.length;
    }

    /** How many bytes the region holds. */
    public int length() {
        return data.length;
    }

    /** {@code 0x00010000..0x00010002 (2 bytes)}. */
    public String describe() {
        return String.format(Locale.ROOT, "0x%08x..0x%08x (%d bytes)", start, end(), length());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HexRegion region && start == region.start && Arrays.equals(data, region.data);
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(start) + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "HexRegion[" + describe() + "]";
    }
}
