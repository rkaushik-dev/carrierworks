package io.github.rkaushikdev.carrierworks.bitstream;

import static java.util.Map.entry;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The configuration packet stream inside a bitstream.
 *
 * <p>Past the header a bitstream is a sequence of thirty two bit words, and it is readable. Some
 * dummy words and a bus width pattern come first, then the sync word {@code 0xaa995566}, and
 * everything after that is packets. A type one packet has a register address and a word count in
 * its header. A type two packet has only a count and inherits the register from the type one before
 * it, which is how a million word frame write fits in a header field eleven bits wide.
 *
 * <p>The bench reads three things out of this. The write to the idcode register is the image's own
 * statement of which device it is for, and it is checked against the part on the board before
 * anything is programmed. The command writes say what kind of image it is, so a partial
 * reconfiguration image is not mistaken for a full one. And the warm boot address in a multiboot
 * image says where the fallback lives.
 *
 * <p>Nothing here modifies a stream. Rewriting a bitstream is a thing the tools do.
 */
public final class Packets {

    /** The word that starts the packet stream. */
    public static final long SYNC_WORD = 0xAA995566L;

    /** The two words the device uses to detect how wide its configuration bus is. */
    public static final List<Long> BUS_WIDTH_PATTERN = List.of(0x000000BBL, 0x11220044L);

    /** How many payload words a packet may carry and still have them kept. */
    public static final int DEFAULT_LIMIT = 100_000;

    /** Configuration register names, by address. */
    public static final Map<Integer, String> REGISTERS = ordered(List.of(
            entry(0, "crc"),
            entry(1, "far"),
            entry(2, "fdri"),
            entry(3, "fdro"),
            entry(4, "cmd"),
            entry(5, "ctl0"),
            entry(6, "mask"),
            entry(7, "stat"),
            entry(8, "lout"),
            entry(9, "cor0"),
            entry(10, "mfwr"),
            entry(11, "cbc"),
            entry(12, "idcode"),
            entry(13, "axss"),
            entry(14, "cor1"),
            entry(16, "wbstar"),
            entry(17, "timer"),
            entry(22, "bootsts"),
            entry(24, "ctl1"),
            entry(31, "bspi")));

    /** Command register values, by the word written. */
    public static final Map<Long, String> COMMANDS = ordered(List.of(
            entry(0L, "null"),
            entry(1L, "wcfg"),
            entry(2L, "mfw"),
            entry(3L, "lfrm"),
            entry(4L, "rcfg"),
            entry(5L, "start"),
            entry(6L, "uram"),
            entry(7L, "rcrc"),
            entry(8L, "aghigh"),
            entry(9L, "switch"),
            entry(10L, "grestore"),
            entry(11L, "shutdown"),
            entry(12L, "gcapture"),
            entry(13L, "desync"),
            entry(15L, "iprog"),
            entry(16L, "crcc"),
            entry(17L, "ltimer")));

    private static final long MAX_WORD = 0xFFFF_FFFFL;

    private Packets() {}

    /** A register's name, or {@code register-N} for an address nobody named. */
    public static String registerName(int address) {
        String name = REGISTERS.get(address);
        return name != null ? name : "register-" + address;
    }

    /** Words as the big endian bytes the device reads them in. */
    public static byte[] wordsToBytes(long... words) {
        ByteBuffer out = ByteBuffer.allocate(Math.multiplyExact(words.length, 4));
        for (long word : words) {
            if (word < 0 || word > MAX_WORD) {
                throw new ValidationError("a configuration word is thirty two bits", "word");
            }
            out.putInt((int) word);
        }
        return out.array();
    }

    /** The same, for words already in a list. */
    public static byte[] wordsToBytes(List<Long> words) {
        return wordsToBytes(words.stream().mapToLong(Long::longValue).toArray());
    }

    /** The unsigned big endian word starting at an offset. */
    public static long wordAt(byte[] data, int offset) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(data).getInt(offset));
    }

    /** Assemble a type one packet header. */
    public static long type1(int opcode, int register, int wordCount) {
        Opcode.of(opcode);
        if (register < 0 || register > 0x3FFF) {
            throw new ValidationError("register address is fourteen bits", "register");
        }
        if (wordCount < 0 || wordCount > 0x7FF) {
            throw new ValidationError("a type one count is eleven bits", "word_count");
        }
        return (0b001L << 29) | ((long) opcode << 27) | ((long) register << 13) | wordCount;
    }

    /** Assemble a type two packet header, which carries only a count. */
    public static long type2(int opcode, int wordCount) {
        Opcode.of(opcode);
        if (wordCount < 0 || wordCount > 0x7FF_FFFF) {
            throw new ValidationError("a type two count is twenty seven bits", "word_count");
        }
        return (0b010L << 29) | ((long) opcode << 27) | wordCount;
    }

    /**
     * The offset of the first word after the sync word.
     *
     * <p>The dummy words and the bus width pattern before it are not packets and are not word
     * aligned to anything in particular, so the sync word is hunted for a byte at a time rather than
     * a word at a time.
     */
    public static int findSync(byte[] data) {
        byte[] needle = wordsToBytes(SYNC_WORD);
        for (int index = 0; index + needle.length <= data.length; index++) {
            if (data[index] == needle[0]
                    && data[index + 1] == needle[1]
                    && data[index + 2] == needle[2]
                    && data[index + 3] == needle[3]) {
                return index + needle.length;
            }
        }
        throw new ValidationError("no sync word in this image", "bitstream");
    }

    /** Read the packet stream, keeping the payload of any packet up to the default limit. */
    public static List<ConfigPacket> parse(byte[] data) {
        return parse(data, DEFAULT_LIMIT);
    }

    /**
     * Read the packet stream, stopping at the end of the data or at a pad word.
     *
     * <p>Frame writes carry hundreds of thousands of words and the bench never looks at them, so the
     * payload of a packet longer than the limit is kept as an empty list and only its place in the
     * stream survives.
     */
    public static List<ConfigPacket> parse(byte[] data, int limit) {
        int offset = findSync(data);
        List<ConfigPacket> packets = new ArrayList<>();
        Optional<Integer> lastRegister = Optional.empty();
        while (offset + 4L <= data.length) {
            long header = wordAt(data, offset);
            offset += 4;
            long kind = header >>> 29;
            Opcode opcode = Opcode.of((int) ((header >>> 27) & 0b11));
            Optional<Integer> register;
            int count;
            if (kind == 0b001) {
                register = Optional.of((int) ((header >>> 13) & 0x3FFF));
                count = (int) (header & 0x7FF);
                lastRegister = register;
            } else if (kind == 0b010) {
                register = lastRegister;
                count = (int) (header & 0x7FF_FFFF);
            } else if (header == MAX_WORD) {
                break;
            } else {
                throw new ValidationError(
                        "unknown packet type in the stream",
                        "bitstream",
                        Details.of("header", String.format(Locale.ROOT, "0x%08x", header)));
            }
            long payloadBytes = count * 4L;
            if (offset + payloadBytes > data.length) {
                throw new ValidationError(
                        "a packet ran off the end of the image", "bitstream", Details.of("wanted", count));
            }
            List<Long> words = count <= limit ? words(data, offset, count) : List.of();
            offset += (int) payloadBytes;
            packets.add(new ConfigPacket(kind == 0b001 ? 1 : 2, opcode, register, words));
        }
        return List.copyOf(packets);
    }

    /** The idcode the image writes, or nothing if it never writes one. */
    public static Optional<Long> idcodeFrom(List<ConfigPacket> packets) {
        for (ConfigPacket packet : packets) {
            if (packet.isWrite()
                    && packet.registerName().equals("idcode")
                    && !packet.words().isEmpty()) {
                return Optional.of(packet.words().getFirst());
            }
        }
        return Optional.empty();
    }

    /** Every command the image issues, in order, by name or as {@code command-N}. */
    public static List<String> commandNames(List<ConfigPacket> packets) {
        List<String> found = new ArrayList<>();
        for (ConfigPacket packet : packets) {
            if (packet.isWrite() && packet.registerName().equals("cmd")) {
                for (long word : packet.words()) {
                    found.add(COMMANDS.getOrDefault(word, "command-" + word));
                }
            }
        }
        return List.copyOf(found);
    }

    private static List<Long> words(byte[] data, int offset, int count) {
        List<Long> words = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            words.add(wordAt(data, offset + index * 4));
        }
        return words;
    }

    private static <K> Map<K, String> ordered(List<Map.Entry<K, String>> entries) {
        Map<K, String> table = new LinkedHashMap<>();
        entries.forEach(item -> table.put(item.getKey(), item.getValue()));
        return Collections.unmodifiableMap(table);
    }
}
