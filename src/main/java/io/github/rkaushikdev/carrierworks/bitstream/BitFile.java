package io.github.rkaushikdev.carrierworks.bitstream;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.util.Repr;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One {@code .bit} file: a handful of tagged fields and then the raw image.
 *
 * <p>The layout has not changed in twenty years. A two byte length, nine bytes of padding, a two
 * byte constant, and then four tagged strings and one tagged blob: {@code a} for the design name,
 * {@code b} for the part, {@code c} for the date, {@code d} for the time and {@code e} for the
 * configuration data with a four byte length. Strings carry their own null terminator inside the
 * declared length, which is the detail every home grown parser gets wrong the first time.
 *
 * <p>The design field is not just a name. The tools append semicolon separated attributes to it,
 * so a real one reads like {@code top_level;UserID=0XFFFFFFFF;Version=2024.1}, and both halves
 * matter. The name goes in the registry and the version says which toolchain produced the image,
 * which is the first thing anyone asks when two builds of the same source behave differently.
 *
 * @param design the design field, attributes and all
 * @param part the part field, device and package run together
 * @param date the build date as the tools wrote it
 * @param time the build time as the tools wrote it
 * @param data the configuration data
 */
public record BitFile(String design, String part, String date, String time, byte[] data) {

    private static final byte[] PREAMBLE = {
        0x00, 0x09, 0x0F, (byte) 0xF0, 0x0F, (byte) 0xF0, 0x0F, (byte) 0xF0, 0x0F, (byte) 0xF0, 0x00, 0x00, 0x01
    };
    private static final List<Character> STRING_TAGS = List.of('a', 'b', 'c', 'd');
    private static final char DATA_TAG = 'e';
    private static final int MAX_FIELD = 0xFFFF;

    // The part field is the device and the package with nothing between them.
    private static final Pattern PART =
            Pattern.compile("(?:xc)?(?<device>\\d[aksv]\\d+t?|\\dz\\d+|[kv]u\\d+p?)(?<package>[a-z]+\\d+)");
    private static final Pattern HEX_NUMBER = Pattern.compile("([+-]?)(?:0[xX]_?)?([0-9a-fA-F]+(?:_[0-9a-fA-F]+)*)");

    public BitFile {
        data = data.clone();
    }

    /** The configuration data; a copy, so the file cannot be changed through it. */
    @Override
    public byte[] data() {
        return data.clone();
    }

    /** The design name without the attributes the tools glue on. */
    public String designName() {
        int separator = design.indexOf(';');
        return (separator < 0 ? design : design.substring(0, separator)).strip();
    }

    /** Whatever came after the design name, keys lower cased, in the order they were written. */
    public Map<String, String> attributes() {
        Map<String, String> found = new LinkedHashMap<>();
        String[] chunks = design.split(";", -1);
        for (int index = 1; index < chunks.length; index++) {
            String chunk = chunks[index];
            int separator = chunk.indexOf('=');
            if (separator >= 0) {
                found.put(
                        chunk.substring(0, separator).strip().toLowerCase(Locale.ROOT),
                        chunk.substring(separator + 1).strip());
            }
        }
        return Collections.unmodifiableMap(found);
    }

    /** The toolchain version the design field names, if it names one. */
    public Optional<String> toolVersion() {
        return Optional.ofNullable(attributes().get("version"));
    }

    /**
     * The user id the design field carries, read as hex.
     *
     * <p>A value that is not hex is reported as missing rather than refused, and so is one too wide
     * for 64 bits, since the field the tools write is 32.
     */
    public Optional<Long> userId() {
        String raw = attributes().get("userid");
        if (raw == null) {
            return Optional.empty();
        }
        Matcher match = HEX_NUMBER.matcher(raw.strip());
        if (!match.matches()) {
            return Optional.empty();
        }
        BigInteger value = new BigInteger(match.group(2).replace("_", ""), 16);
        if (match.group(1).equals("-")) {
            value = value.negate();
        }
        return value.bitLength() < Long.SIZE ? Optional.of(value.longValue()) : Optional.empty();
    }

    /**
     * The device code, with the package stripped off.
     *
     * <p>The part field carries something like {@code 7a100tfgg484}, which is the device without
     * its leading {@code xc} and with the package glued on the end. The bench works in full device
     * codes, so this puts the {@code xc} back and cuts at the package.
     *
     * @throws ValidationError when the part field does not read as a device and a package
     */
    public String device() {
        return "xc" + splitPart().group("device");
    }

    /** The package code from the end of the part field. */
    public String packageCode() {
        return splitPart().group("package");
    }

    /** How many bytes of configuration data the file carries. */
    public int length() {
        return data.length;
    }

    private Matcher splitPart() {
        String raw = part.strip().toLowerCase(Locale.ROOT);
        Matcher match = PART.matcher(raw);
        if (!match.matches()) {
            throw new ValidationError("cannot read the part out of a bit file", "part", Details.of("given", raw));
        }
        return match;
    }

    /** Take a {@code .bit} file apart, header and image. */
    public static BitFile parse(byte[] data) {
        if (data.length < PREAMBLE.length + 8) {
            throw new ValidationError("too short to be a bit file", "bitfile", Details.of("length", data.length));
        }
        if (!Arrays.equals(data, 0, PREAMBLE.length, PREAMBLE, 0, PREAMBLE.length)) {
            throw new ValidationError("that is not a bit file header", "bitfile");
        }
        int offset = PREAMBLE.length;
        String[] fields = new String[STRING_TAGS.size()];
        for (int index = 0; index < fields.length; index++) {
            if (offset >= data.length) {
                throw new ValidationError("bit file ended before its fields did", "bitfile");
            }
            String expected = String.valueOf(STRING_TAGS.get(index));
            String found = String.valueOf((char) Byte.toUnsignedInt(data[offset]));
            if (!found.equals(expected)) {
                throw new ValidationError(
                        "bit file fields are out of order",
                        "bitfile",
                        Details.of("expected", expected, "given", found));
            }
            offset += 1;
            if (offset + 2 > data.length) {
                throw new ValidationError("bit file ended inside a field length", "bitfile");
            }
            int length = (Byte.toUnsignedInt(data[offset]) << 8) | Byte.toUnsignedInt(data[offset + 1]);
            offset += 2;
            if (offset + length > data.length) {
                throw new ValidationError("bit file ended inside a field", "bitfile");
            }
            if (length == 0 || data[offset + length - 1] != 0) {
                throw new ValidationError("a bit file string field is null terminated", "bitfile");
            }
            fields[index] = new String(data, offset, length - 1, StandardCharsets.US_ASCII);
            offset += length;
        }
        if (offset >= data.length || Byte.toUnsignedInt(data[offset]) != DATA_TAG) {
            throw new ValidationError("bit file has no data field", "bitfile");
        }
        offset += 1;
        if (offset + 4 > data.length) {
            throw new ValidationError("bit file ended inside the data length", "bitfile");
        }
        long claimed = Packets.wordAt(data, offset);
        offset += 4;
        int present = (int) Math.min(claimed, data.length - offset);
        if (present != claimed) {
            throw new ValidationError(
                    "bit file is shorter than its data length claims",
                    "bitfile",
                    Details.of("claimed", claimed, "present", present));
        }
        byte[] body = Arrays.copyOfRange(data, offset, offset + present);
        return new BitFile(fields[0], fields[1], fields[2], fields[3], body);
    }

    /**
     * Write a {@code .bit} file, which is how the tests get one and how the bench hands a trimmed
     * image back to somebody.
     *
     * @throws ValidationError when a field is too long for its two byte length
     * @throws IllegalArgumentException when a field holds anything but ASCII
     */
    public static byte[] build(String design, String part, String date, String time, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(PREAMBLE);
        List<String> values = List.of(design, part, date, time);
        for (int index = 0; index < values.size(); index++) {
            byte[] encoded = ascii(values.get(index));
            int length = encoded.length + 1;
            if (length > MAX_FIELD) {
                throw new ValidationError("bit file field is too long", String.valueOf(STRING_TAGS.get(index)));
            }
            out.write(STRING_TAGS.get(index));
            out.write(length >>> 8);
            out.write(length);
            out.writeBytes(encoded);
            out.write(0);
        }
        out.write(DATA_TAG);
        out.writeBytes(Packets.wordsToBytes(data.length));
        out.writeBytes(data);
        return out.toByteArray();
    }

    private static byte[] ascii(String value) {
        if (!value.chars().allMatch(ch -> ch <= 0x7F)) {
            throw new IllegalArgumentException("a bit file field holds only ASCII: " + Repr.of(value));
        }
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BitFile file
                && design.equals(file.design)
                && part.equals(file.part)
                && date.equals(file.date)
                && time.equals(file.time)
                && Arrays.equals(data, file.data);
    }

    @Override
    public int hashCode() {
        int hash = design.hashCode();
        hash = 31 * hash + part.hashCode();
        hash = 31 * hash + date.hashCode();
        hash = 31 * hash + time.hashCode();
        return 31 * hash + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "BitFile[design=" + design + ", part=" + part + ", date=" + date + ", time=" + time + ", data="
                + data.length + " bytes]";
    }
}
