package io.github.rkaushikdev.carrierworks.bitstream;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Locale;

/**
 * The read command an image tells the device to fetch the rest of itself from flash with.
 *
 * <p>Master SPI configuration starts out slow and one bit wide, reads the first part of the image,
 * and then switches to whatever the image asks for in its {@code bspi} register write. The low byte
 * of that write is a SPI NOR read opcode, and the opcode alone says everything the bench cares
 * about: how many data lines the read uses and whether it sends three address bytes or four.
 *
 * <p>Both of those are constraints on the board rather than on the image. A quad read on a part
 * that is not wired for quad, or that has its quad enable bit clear, reads back garbage after the
 * switch. A three byte read cannot see past sixteen megabytes, so an image that lives above that
 * line has to use one of the four byte opcodes or the device never finds it.
 */
public enum SpiRead {
    /** The plain read every part answers. */
    READ(0x03, 1, 3),
    /** Fast read, one line, with a dummy byte. */
    FAST_READ(0x0B, 1, 3),
    /** Dual output read. */
    DUAL_READ(0x3B, 2, 3),
    /** Quad output read. */
    QUAD_READ(0x6B, 4, 3),
    /** Plain read with four address bytes. */
    READ_4B(0x13, 1, 4),
    /** Fast read with four address bytes. */
    FAST_READ_4B(0x0C, 1, 4),
    /** Dual output read with four address bytes. */
    DUAL_READ_4B(0x3C, 2, 4),
    /** Quad output read with four address bytes. */
    QUAD_READ_4B(0x6C, 4, 4);

    private static final long THREE_BYTE_REACH = 1L << 24;

    private final int opcode;
    private final int busWidth;
    private final int addressBytes;

    SpiRead(int opcode, int busWidth, int addressBytes) {
        this.opcode = opcode;
        this.busWidth = busWidth;
        this.addressBytes = addressBytes;
    }

    /** The opcode the device sends. */
    public int opcode() {
        return opcode;
    }

    /** How many data lines the read uses: one, two or four. */
    public int busWidth() {
        return busWidth;
    }

    /** How many address bytes follow the opcode. */
    public int addressBytes() {
        return addressBytes;
    }

    /** Whether the read needs the part wired and enabled for four bit transfers. */
    public boolean needsQuad() {
        return busWidth == 4;
    }

    /** Whether an address is one this read can send. */
    public boolean reaches(long address) {
        return addressBytes == 4 || address < THREE_BYTE_REACH;
    }

    /**
     * The read behind an opcode.
     *
     * @throws ValidationError when the opcode is not a read the device can be told to use
     */
    public static SpiRead of(int opcode) {
        for (SpiRead read : values()) {
            if (read.opcode == opcode) {
                return read;
            }
        }
        throw new ValidationError(
                "not a spi read opcode", "bspi", Details.of("given", String.format(Locale.ROOT, "0x%02x", opcode)));
    }

    /** {@code x4 read 0x6c, 4 address bytes}. */
    public String describe() {
        return String.format(Locale.ROOT, "x%d read 0x%02x, %d address bytes", busWidth, opcode, addressBytes);
    }
}
