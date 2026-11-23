package io.github.rkaushikdev.carrierworks.bitstream;

import java.util.Locale;
import java.util.Optional;

/**
 * The check the device runs on its own configuration stream, run here first.
 *
 * <p>The device keeps a running CRC-32C over every register write it takes: each payload word with
 * the low five bits of its register address above it, thirty seven bits, fed in least significant
 * bit first. A write to the {@code crc} register carries the value the tools computed, the device
 * compares, and a mismatch stops configuration with the error bit set and the done light off. A
 * {@code rcrc} command starts the count again from zero, and so does each comparison.
 *
 * <p>Running the same check when an image comes in turns a bitstream that was damaged on the way,
 * by a copy, a bad download or a text mode transfer, into a refusal at upload rather than a board
 * that will not come up and a morning spent suspecting the cable. An image built with the CRC turned
 * off writes no {@code crc} register at all, and that is reported as nothing checked rather than as
 * a pass.
 *
 * @param checked how many {@code crc} writes the stream carries
 * @param failed how many of them disagree with the stream
 * @param firstMismatch the first disagreement, as the expected and computed values, or empty
 */
public record PacketCrc(int checked, int failed, Optional<String> firstMismatch) {

    private static final long CASTAGNOLI = 0x82F6_3B78L;
    private static final int CRC_REGISTER = 0;
    private static final int CMD_REGISTER = 4;
    private static final long RCRC = 7;
    private static final int ADDRESS_BITS = 5;
    private static final int FED_BITS = 32 + ADDRESS_BITS;

    /** Run the device's check over a configuration stream. */
    public static PacketCrc check(byte[] data) {
        Tally tally = new Tally();
        Packets.walk(data, (kind, opcode, register, offset, count) -> {
            if (opcode != Opcode.WRITE || register.isEmpty()) {
                return;
            }
            int address = register.get();
            for (int index = 0; index < count; index++) {
                tally.write(address, Packets.wordAt(data, offset + index * 4));
            }
        });
        return new PacketCrc(tally.checked, tally.failed, Optional.ofNullable(tally.mismatch));
    }

    /** The running value after one more register write. */
    public static long next(long crc, int register, long word) {
        long value = ((long) (register & ((1 << ADDRESS_BITS) - 1)) << 32) | word;
        long running = crc;
        for (int bit = 0; bit < FED_BITS; bit++) {
            boolean feedback = ((value ^ running) & 1) != 0;
            running >>>= 1;
            if (feedback) {
                running ^= CASTAGNOLI;
            }
            value >>>= 1;
        }
        return running;
    }

    /** Whether the stream carries at least one check and every one of them holds. */
    public boolean passed() {
        return checked > 0 && failed == 0;
    }

    /** {@code crc ok, 1 check}, {@code crc not written}, or the first mismatch. */
    public String describe() {
        if (checked == 0) {
            return "crc not written";
        }
        if (failed == 0) {
            return "crc ok, " + checked + (checked == 1 ? " check" : " checks");
        }
        return "crc failed " + failed + " of " + checked + ": " + firstMismatch.orElse("");
    }

    private static final class Tally {
        private long crc;
        private int checked;
        private int failed;
        private String mismatch;

        void write(int register, long word) {
            if (register == CRC_REGISTER) {
                checked++;
                if (word != crc && mismatch == null) {
                    mismatch = String.format(Locale.ROOT, "image says 0x%08x, stream gives 0x%08x", word, crc);
                }
                failed += word != crc ? 1 : 0;
                crc = 0;
            } else if (register == CMD_REGISTER && word == RCRC) {
                crc = 0;
            } else {
                crc = next(crc, register, word);
            }
        }
    }
}
