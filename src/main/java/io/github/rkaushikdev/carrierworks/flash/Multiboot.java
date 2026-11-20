package io.github.rkaushikdev.carrierworks.flash;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.bitstream.Binary;
import io.github.rkaushikdev.carrierworks.bitstream.Packets;
import java.util.Optional;

/**
 * The jump that makes a golden image a fallback rather than a dead end.
 *
 * <p>Multiboot works by putting a very short sequence at the very start of the flash that tells the
 * device to go and load a different address instead. Sync, a write to the warm boot start address
 * register, then the internal reprogram command. The device reads that, sets its address, restarts
 * configuration from there, and if the image it finds is corrupt it falls back to address zero,
 * where the golden image sits.
 *
 * <p>The sequence is eight words long and every one of them matters. The dummy word lets the device
 * find the clock, the sync word starts the packet stream, the noop after the command gives the
 * internal restart somewhere to land. Writing it by hand from the user guide is how the lab did it
 * before this existed, and the one time a noop was left out the board came up on the golden image
 * with no indication that anything had been skipped.
 *
 * <p>The address written into the register is a byte address in the flash, so it is the same number
 * the layout uses for the region.
 */
public final class Multiboot {

    /** How many words the jump takes. */
    public static final int MULTIBOOT_WORDS = 8;

    private static final long DUMMY = 0xFFFF_FFFFL;
    private static final long NOOP = Packets.type1(0, 0, 0);
    private static final int WBSTAR = 16;
    private static final int CMD = 4;
    private static final long IPROG = 15;
    private static final long WRITE_WBSTAR = Packets.type1(2, WBSTAR, 1);
    private static final long WRITE_CMD = Packets.type1(2, CMD, 1);

    // The warm boot address is twenty nine bits; the two above it are the read settings for the
    // flash and are left at zero for every part the lab uses.
    private static final long ADDRESS_MASK = 0x1FFF_FFFFL;

    private Multiboot() {}

    /** The eight word sequence that sends the device to another address. */
    public static byte[] warmBootPrefix(long address) {
        if (address < 0 || address > ADDRESS_MASK) {
            throw new ValidationError(
                    "a warm boot address is twenty nine bits", "address", Details.of("given", address));
        }
        return Packets.wordsToBytes(DUMMY, Packets.SYNC_WORD, NOOP, WRITE_WBSTAR, address, WRITE_CMD, IPROG, NOOP);
    }

    /**
     * The address a prefix jumps to, or nothing if this is not one.
     *
     * <p>Deliberately strict. Anything that is not exactly the sequence written here is treated as
     * an ordinary image, because a partial match is far more likely to be a bitstream that happens
     * to start with a sync word than a multiboot header somebody hand assembled.
     */
    public static Optional<Long> readWarmBootAddress(byte[] data) {
        if (data.length < MULTIBOOT_WORDS * 4) {
            return Optional.empty();
        }
        long[] words = new long[MULTIBOOT_WORDS];
        for (int index = 0; index < words.length; index++) {
            words[index] = Packets.wordAt(data, index * 4);
        }
        if (words[0] != DUMMY || words[1] != Packets.SYNC_WORD || words[2] != NOOP) {
            return Optional.empty();
        }
        if (words[3] != WRITE_WBSTAR) {
            return Optional.empty();
        }
        if (words[5] != WRITE_CMD || words[6] != IPROG) {
            return Optional.empty();
        }
        return Optional.of(words[4] & ADDRESS_MASK);
    }

    /** The whole flash image, the gap filled with the value erased flash reads as. */
    public static byte[] assemble(FlashLayout layout, byte[] golden, byte[] update) {
        return assemble(layout, golden, update, Binary.ERASED);
    }

    /**
     * The whole flash image: jump, golden, gap, update.
     *
     * <p>The prefix goes in front of the golden image rather than in a region of its own, because
     * the device reads it as the first words of whatever is at address zero and the golden image has
     * to follow it immediately.
     *
     * @throws ValidationError when the golden region is not at zero or either image does not fit
     */
    public static byte[] assemble(FlashLayout layout, byte[] golden, byte[] update, int fill) {
        FlashRegion goldenRegion = layout.region("golden");
        FlashRegion updateRegion = layout.region("update");
        if (goldenRegion.start() != 0) {
            throw new ValidationError(
                    "the golden image has to sit at address zero", "layout", Details.of("start", goldenRegion.start()));
        }
        byte[] head = concat(warmBootPrefix(updateRegion.start()), golden);
        if (!goldenRegion.holds(head.length)) {
            throw new ValidationError(
                    "the golden image and its jump do not fit their region",
                    "golden",
                    Details.of("wanted", head.length, "room", goldenRegion.length()));
        }
        if (!updateRegion.holds(update.length)) {
            throw new ValidationError(
                    "the update image does not fit its region",
                    "update",
                    Details.of("wanted", update.length, "room", updateRegion.length()));
        }
        return concat(Binary.padTo(head, updateRegion.start(), fill), update);
    }

    /** The golden image with its jump in front, for programming on its own. */
    public static byte[] goldenWithJump(FlashLayout layout, byte[] golden) {
        return concat(warmBootPrefix(layout.region("update").start()), golden);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = new byte[Math.addExact(first.length, second.length)];
        System.arraycopy(first, 0, out, 0, first.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }
}
