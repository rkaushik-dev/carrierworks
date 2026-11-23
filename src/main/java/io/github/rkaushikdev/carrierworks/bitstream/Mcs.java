package io.github.rkaushikdev.carrierworks.bitstream;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The Intel hex form the flash programmers eat, written and read back.
 *
 * <p>An {@code .mcs} file is Intel hex with nothing exotic in it: data records, an extended linear
 * address record every sixty four kilobytes because the address field is only sixteen bits wide,
 * and an end of file record. The checksum is the two's complement of the sum of every byte in the
 * record, which catches a corrupted line and nothing else, but it is what the format says and a
 * programmer that reads one will refuse a file without it.
 *
 * <p>Record length is sixteen bytes because that is what the vendor tool writes and because a
 * sixteen megabyte image at sixteen bytes a line is a file people can still open in an editor when
 * something has gone wrong. Thirty two is allowed and halves the size; anything else works and
 * looks unfamiliar, which costs more than it saves.
 */
public final class Mcs {

    /** A record carrying image bytes. */
    public static final int DATA = 0x00;

    /** The record that ends the file. */
    public static final int END_OF_FILE = 0x01;

    /** A record carrying the upper sixteen bits of the addresses that follow. */
    public static final int EXTENDED_LINEAR = 0x04;

    /** A record carrying the paragraph, sixteen bytes a step, the addresses that follow sit above. */
    public static final int EXTENDED_SEGMENT = 0x02;

    /** A record carrying the address a processor would start executing at. */
    public static final int START_LINEAR = 0x05;

    /** How many bytes a data record carries unless asked otherwise. */
    public static final int DEFAULT_RECORD_BYTES = 16;

    private static final long SEGMENT = 0x10000;
    private static final long PARAGRAPH = 16;
    private static final long ADDRESS_SPACE = 0x1_0000_0000L;
    private static final HexFormat UPPER_HEX = HexFormat.of().withUpperCase();

    private Mcs() {}

    /** One Intel hex line, checksum and all. */
    public static String hexRecord(int address, int recordType, byte[] payload) {
        if (address < 0 || address > 0xFFFF) {
            throw new ValidationError("a hex record address is sixteen bits", "address");
        }
        if (recordType < 0 || recordType > 0xFF) {
            throw new ValidationError("a record type is one byte", "record_type");
        }
        if (payload.length > 0xFF) {
            throw new ValidationError("a hex record holds at most 255 bytes", "payload");
        }
        byte[] line = new byte[payload.length + 5];
        line[0] = (byte) payload.length;
        line[1] = (byte) (address >>> 8);
        line[2] = (byte) address;
        line[3] = (byte) recordType;
        System.arraycopy(payload, 0, line, 4, payload.length);
        line[line.length - 1] = (byte) -sum(line, line.length - 1);
        return ":" + UPPER_HEX.formatHex(line);
    }

    /**
     * Every record for an image, in order, produced as they are read rather than all at once.
     *
     * @throws ValidationError when the record length or the start address cannot be written
     */
    public static Stream<String> records(byte[] data, long startAddress, int recordBytes) {
        if (recordBytes < 1 || recordBytes > 0xFF) {
            throw new ValidationError("record length is out of range", "record_bytes");
        }
        if (startAddress < 0) {
            throw new ValidationError("a start address is not negative", "start_address");
        }
        if (startAddress % SEGMENT != 0) {
            throw new ValidationError(
                    "an mcs image starts on a sixty four kilobyte boundary",
                    "start_address",
                    Details.of("given", startAddress));
        }
        if (startAddress + data.length > ADDRESS_SPACE) {
            throw new ValidationError("that image does not fit in a thirty two bit address space");
        }
        Iterator<String> lines = new RecordIterator(data.clone(), startAddress, recordBytes);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(lines, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    /** The whole file for an image loading at address zero, sixteen bytes a record. */
    public static String write(byte[] data) {
        return write(data, 0, DEFAULT_RECORD_BYTES);
    }

    /** The whole file for an image loading part way up a part, sixteen bytes a record. */
    public static String write(byte[] data, long startAddress) {
        return write(data, startAddress, DEFAULT_RECORD_BYTES);
    }

    /** The whole file as text, each record ended by a line feed the way the tools write it. */
    public static String write(byte[] data, long startAddress, int recordBytes) {
        StringBuilder out = new StringBuilder();
        records(data, startAddress, recordBytes)
                .forEach(record -> out.append(record).append('\n'));
        return out.toString();
    }

    /**
     * Read a file back into the runs of bytes it holds.
     *
     * <p>A multiboot file has a hole in the middle, so the answer is a list of regions rather than
     * one image. Joining them back together is the caller's decision because it needs a fill value
     * and the caller knows which one.
     *
     * <p>Files from other programmers are read too. Some address past sixty four kilobytes with
     * extended segment records, sixteen byte paragraphs in the old real mode way, instead of linear
     * ones, and some end with a start linear address record naming where a processor would begin
     * executing. A flash image has no entry point, so that record is checked and then set aside.
     *
     * @throws ValidationError naming the line, when a record is malformed or the file never ends
     */
    public static List<HexRegion> read(String text) {
        List<Run> runs = new ArrayList<>();
        long base = 0;
        boolean ended = false;
        List<String> lines = lines(text);
        for (int index = 0; index < lines.size(); index++) {
            int number = index + 1;
            String line = lines.get(index).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (ended) {
                throw malformed("records after the end of file record", number);
            }
            HexLine record = HexLine.decode(line, number);
            switch (record.type()) {
                case END_OF_FILE -> ended = true;
                case EXTENDED_LINEAR -> base = record.upperWord("a segment record carries two bytes") * SEGMENT;
                case EXTENDED_SEGMENT -> base = record.upperWord("a segment record carries two bytes") * PARAGRAPH;
                case START_LINEAR -> record.expectLength(4, "a start address record carries four bytes");
                case DATA -> {
                    long start = base + record.address();
                    if (!runs.isEmpty() && runs.getLast().end() == start) {
                        runs.getLast().bytes.writeBytes(record.payload());
                    } else {
                        runs.add(new Run(start, record.payload()));
                    }
                }
                default ->
                    throw new ValidationError(
                            "unsupported hex record type",
                            "mcs",
                            Details.of("line", number, "record_type", record.type()));
            }
        }
        if (!ended) {
            throw new ValidationError("the file has no end of file record", "mcs");
        }
        return runs.stream()
                .map(run -> new HexRegion(run.start, run.bytes.toByteArray()))
                .toList();
    }

    /** Join regions into one, filling the holes with the value erased flash reads as. */
    public static HexRegion flatten(List<HexRegion> regions) {
        return flatten(regions, Binary.ERASED);
    }

    /**
     * Join regions into one, filling the holes.
     *
     * <p>The answer keeps the address the image starts at along with the bytes, because a file that
     * begins part way up a part is normal and assuming zero would put every later address out by
     * the offset. Nothing at all joins to nothing at address zero.
     *
     * @throws ValidationError when the fill is not a byte or two regions overlap
     */
    public static HexRegion flatten(List<HexRegion> regions, int fill) {
        if (regions.isEmpty()) {
            return new HexRegion(0, new byte[0]);
        }
        if (fill < 0 || fill > 0xFF) {
            throw new ValidationError("the fill is one byte", "fill", Details.of("given", fill));
        }
        List<HexRegion> ordered = new ArrayList<>(regions);
        ordered.sort(Comparator.comparingLong(HexRegion::start));
        long start = ordered.getFirst().start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (HexRegion region : ordered) {
            long offset = region.start() - start;
            if (offset < out.size()) {
                throw new ValidationError(
                        "two regions overlap",
                        "regions",
                        Details.of("at", String.format(Locale.ROOT, "0x%08x", region.start())));
            }
            byte[] hole = new byte[Math.toIntExact(offset - out.size())];
            Arrays.fill(hole, (byte) fill);
            out.writeBytes(hole);
            out.writeBytes(region.data());
        }
        return new HexRegion(start, out.toByteArray());
    }

    private static ValidationError malformed(String message, int line) {
        return new ValidationError(message, "mcs", Details.of("line", line));
    }

    private static int sum(byte[] bytes, int length) {
        int total = 0;
        for (int index = 0; index < length; index++) {
            total += Byte.toUnsignedInt(bytes[index]);
        }
        return total;
    }

    /**
     * Hex digits as bytes, ASCII white space allowed between pairs but not inside one.
     *
     * <p>Empty when anything else is in the way, an odd digit is left over, or a pair is split.
     */
    private static Optional<byte[]> fromHex(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() / 2);
        int index = 0;
        while (index < text.length()) {
            if (isAsciiSpace(text.charAt(index))) {
                index++;
                continue;
            }
            if (index + 1 >= text.length()) {
                return Optional.empty();
            }
            char high = text.charAt(index);
            char low = text.charAt(index + 1);
            if (!isAsciiHex(high) || !isAsciiHex(low)) {
                return Optional.empty();
            }
            out.write((Character.digit(high, 16) << 4) | Character.digit(low, 16));
            index += 2;
        }
        return Optional.of(out.toByteArray());
    }

    private static boolean isAsciiHex(char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
    }

    private static boolean isAsciiSpace(char ch) {
        return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000B' || ch == '\f';
    }

    /**
     * The lines of a text, split at every line boundary Unicode names and with the breaks dropped.
     *
     * <p>A break at the very end does not start another line, so a file ending in a newline has as
     * many lines as it has records.
     */
    private static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (!isLineBreak(ch)) {
                index++;
                continue;
            }
            lines.add(text.substring(start, index));
            boolean crlf = ch == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n';
            index += crlf ? 2 : 1;
            start = index;
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    private static boolean isLineBreak(char ch) {
        return switch (ch) {
            case '\n', '\r', '\u000B', '\f', '\u001C', '\u001D', '\u001E', '\u0085', ' ', ' ' -> true;
            default -> false;
        };
    }

    /** One line of a hex file, checked and taken apart. */
    private record HexLine(int number, int type, int address, byte[] payload) {

        static HexLine decode(String line, int number) {
            if (!line.startsWith(":")) {
                throw malformed("a hex record starts with a colon", number);
            }
            byte[] body = fromHex(line.substring(1)).orElseThrow(() -> malformed("a hex record is hex", number));
            if (body.length < 5) {
                throw malformed("a hex record is at least five bytes", number);
            }
            if ((sum(body, body.length) & 0xFF) != 0) {
                throw malformed("a hex record failed its checksum", number);
            }
            byte[] payload = Arrays.copyOfRange(body, 4, body.length - 1);
            if (payload.length != Byte.toUnsignedInt(body[0])) {
                throw malformed("a hex record lied about its length", number);
            }
            int address = (Byte.toUnsignedInt(body[1]) << 8) | Byte.toUnsignedInt(body[2]);
            return new HexLine(number, Byte.toUnsignedInt(body[3]), address, payload);
        }

        void expectLength(int length, String message) {
            if (payload.length != length) {
                throw malformed(message, number);
            }
        }

        long upperWord(String message) {
            expectLength(2, message);
            return (Byte.toUnsignedInt(payload[0]) << 8) | Byte.toUnsignedInt(payload[1]);
        }
    }

    /** One run of contiguous data being read. */
    private static final class Run {

        private final long start;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        private Run(long start, byte[] first) {
            this.start = start;
            bytes.writeBytes(first);
        }

        private long end() {
            return start + bytes.size();
        }
    }

    /** The records of one image, a segment record ahead of each sixty four kilobytes. */
    private static final class RecordIterator implements Iterator<String> {

        private final byte[] data;
        private final long startAddress;
        private final int recordBytes;
        private long segment = -1;
        private int offset;
        private boolean finished;

        private RecordIterator(byte[] data, long startAddress, int recordBytes) {
            this.data = data;
            this.startAddress = startAddress;
            this.recordBytes = recordBytes;
        }

        @Override
        public boolean hasNext() {
            return !finished;
        }

        @Override
        public String next() {
            if (finished) {
                throw new NoSuchElementException();
            }
            if (offset >= data.length) {
                finished = true;
                return hexRecord(0, END_OF_FILE, new byte[0]);
            }
            long address = startAddress + offset;
            if (address / SEGMENT != segment) {
                segment = address / SEGMENT;
                return hexRecord(0, EXTENDED_LINEAR, new byte[] {(byte) (segment >>> 8), (byte) segment});
            }
            long room = SEGMENT - (address % SEGMENT);
            int take = (int) Math.min(Math.min(recordBytes, room), data.length - offset);
            byte[] payload = Arrays.copyOfRange(data, offset, offset + take);
            offset += take;
            return hexRecord((int) (address % SEGMENT), DATA, payload);
        }
    }
}
