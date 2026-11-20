package io.github.rkaushikdev.carrierworks.bitstream;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turning an image into the bytes a flash part or a driver wants.
 *
 * <p>Three conversions live here and each one exists because a different piece of hardware counts
 * bits in a different direction.
 *
 * <p>A {@code .bin} file is the configuration data with the header thrown away, which is what a
 * driver shifts straight into the device. A flash image is that with the bits inside each byte
 * reversed, because the device reads SPI flash most significant bit first while the flash returns
 * bytes least significant bit first, and the tools compensate at write time rather than at read
 * time. A word swapped image is for the parallel interfaces that latch sixteen or thirty two bits at
 * a time.
 *
 * <p>Every one of these is its own inverse, which is the only reason a mistake here is survivable:
 * apply it twice and you are back where you started. That is also why the tests check the round
 * trip rather than the byte values, except in the one place where a fixed pattern proves the
 * direction.
 */
public final class Binary {

    /** An erased flash cell reads as ones, so padding is {@code 0xff} and never {@code 0x00}. */
    public static final int ERASED = 0xFF;

    // Bit reversal for every byte value, built once because a flash image is several megabytes and
    // doing this a bit at a time is measurably slow.
    private static final byte[] REVERSED = new byte[256];

    static {
        for (int value = 0; value < REVERSED.length; value++) {
            REVERSED[value] = (byte) (Integer.reverse(value) >>> 24);
        }
    }

    private Binary() {}

    /** Reverse the bits inside each byte, leaving the byte order alone. */
    public static byte[] reverseBits(byte[] data) {
        byte[] out = new byte[data.length];
        for (int index = 0; index < data.length; index++) {
            out[index] = REVERSED[Byte.toUnsignedInt(data[index])];
        }
        return out;
    }

    /** Reverse the byte order inside each pair of bytes. */
    public static byte[] byteSwap(byte[] data) {
        return byteSwap(data, 2);
    }

    /** Reverse the byte order inside each group of {@code width} bytes. */
    public static byte[] byteSwap(byte[] data, int width) {
        if (width < 2) {
            throw new ValidationError("a swap needs at least two bytes", "width", Details.of("given", width));
        }
        if (data.length % width != 0) {
            throw new ValidationError(
                    "the image is not a whole number of words",
                    "data",
                    Details.of("width", width, "length", data.length));
        }
        byte[] out = new byte[data.length];
        for (int offset = 0; offset < data.length; offset += width) {
            for (int index = 0; index < width; index++) {
                out[offset + index] = data[offset + width - 1 - index];
            }
        }
        return out;
    }

    /** The thirty two bit case, which is what the parallel loaders want. */
    public static byte[] wordSwap(byte[] data) {
        return byteSwap(data, 4);
    }

    /** Pad an image out to a length with the value an erased part reads as. */
    public static byte[] padTo(byte[] data, long length) {
        return padTo(data, length, ERASED);
    }

    /**
     * Pad an image out to a length with a fill byte.
     *
     * @throws ValidationError when the length is negative or shorter than the image, or the fill is
     *     not a byte
     */
    public static byte[] padTo(byte[] data, long length, int fill) {
        if (length < 0) {
            throw new ValidationError("cannot pad to a negative length", "length", Details.of("given", length));
        }
        if (fill < 0 || fill > 0xFF) {
            throw new ValidationError("the fill is one byte", "fill", Details.of("given", fill));
        }
        if (data.length > length) {
            throw new ValidationError(
                    "the image is already longer than that",
                    "length",
                    Details.of("wanted", length, "have", data.length));
        }
        byte[] out = Arrays.copyOf(data, Math.toIntExact(length));
        Arrays.fill(out, data.length, out.length, (byte) fill);
        return out;
    }

    /** Drop the erased padding off the end, which is how a readback is compared. */
    public static byte[] trimErased(byte[] data) {
        return trimErased(data, ERASED);
    }

    /** Drop a trailing run of one fill value off the end. */
    public static byte[] trimErased(byte[] data, int fill) {
        int end = data.length;
        while (end > 0 && Byte.toUnsignedInt(data[end - 1]) == fill) {
            end--;
        }
        return Arrays.copyOf(data, end);
    }

    /** Cut an image into page sized writes, the last one possibly short. */
    public static List<byte[]> splitPages(byte[] data, int page) {
        if (page <= 0) {
            throw new ValidationError("a page has a size", "page", Details.of("given", page));
        }
        List<byte[]> pages = new ArrayList<>();
        for (int offset = 0; offset < data.length; offset += page) {
            pages.add(Arrays.copyOfRange(data, offset, Math.min(data.length, offset + page)));
        }
        return List.copyOf(pages);
    }

    /** The configuration data with the header removed. */
    public static byte[] toBin(Bitstream image) {
        return image.header().data();
    }

    /** The bytes to lay into SPI flash, bit reversed. */
    public static byte[] toFlashImage(Bitstream image) {
        return toFlashImage(image, true);
    }

    /**
     * The bytes to lay into SPI flash.
     *
     * <p>Swapping is the normal case and the reason is above. It is switchable because a couple of
     * the older carriers in the lab have the swap done in their own boot code, and programming a
     * swapped image into one of those produces a device that never comes out of reset with no other
     * symptom.
     */
    public static byte[] toFlashImage(Bitstream image, boolean swap) {
        byte[] data = toBin(image);
        return swap ? reverseBits(data) : data;
    }
}
