package io.github.rkaushikdev.carrierworks.catalog;

import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.util.Repr;
import java.util.Locale;
import java.util.Map;

/**
 * The 32 bits every device on a chain shifts out when you ask who it is, held as the four fields it
 * actually is.
 *
 * <p>The layout is fixed by IEEE 1149.1 and it is worth spelling out because three different bugs
 * hide in it. Bit zero is always one, so a device that reads back with bit zero clear is a wiring
 * fault rather than an unknown part. The eleven bits above that are the manufacturer, in the JEDEC
 * encoding where the count of continuation bytes goes in the top four, which is why ARM comes out
 * as {@code 0x23b} and not as something that looks like a number anyone chose. The version nibble
 * at the top changes with the silicon stepping, so nothing may compare on it.
 *
 * <p>All ones is the other special case. An open chain reads as all ones because the line floats
 * high, so {@code 0xffffffff} is never a device and always a broken cable.
 *
 * @param value the register, as an unsigned 32 bit number
 */
public record IdCode(long value) {

    /** What an open chain reads as. */
    public static final long ALL_ONES = 0xFFFF_FFFFL;

    /** JEDEC manufacturer identifiers, in the form the idcode carries them. */
    public static final Map<Integer, String> MANUFACTURERS = Map.of(
            0x049, "xilinx",
            0x043, "lattice",
            0x06E, "altera",
            0x0CF, "microsemi",
            0x23B, "arm",
            0x034, "cypress",
            0x015, "micron");

    public IdCode {
        if (value < 0 || value > ALL_ONES) {
            throw new ValidationError("idcode does not fit in 32 bits", "idcode");
        }
    }

    /** A JEDEC id as a name, or the raw value when the lab has not met it. */
    public static String manufacturerName(int code) {
        String name = MANUFACTURERS.get(code);
        return name != null ? name : String.format(Locale.ROOT, "unknown-0x%03x", code);
    }

    /**
     * Read an idcode from a scan log, a config file or a person: hex, with or without {@code 0x},
     * underscores and spaces ignored.
     */
    public static IdCode parse(String text) {
        String cleaned = text.strip().toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
        if (cleaned.isEmpty()) {
            throw new ValidationError("empty idcode", "idcode");
        }
        return new IdCode(hex(cleaned, text));
    }

    /** An idcode a driver already read as a number. */
    public static IdCode parse(long value) {
        return new IdCode(value);
    }

    /** Assemble an idcode from its fields, for tests and for fakes. */
    public static IdCode build(int manufacturer, int part, int version) {
        if (manufacturer < 0 || manufacturer > 0x7FF) {
            throw new ValidationError("manufacturer is eleven bits", "manufacturer");
        }
        if (part < 0 || part > 0xFFFF) {
            throw new ValidationError("part number is sixteen bits", "part");
        }
        if (version < 0 || version > 0xF) {
            throw new ValidationError("version is four bits", "version");
        }
        return new IdCode(((long) version << 28) | ((long) part << 12) | ((long) manufacturer << 1) | 1);
    }

    /** The silicon stepping in the top nibble. */
    public int version() {
        return (int) ((value >> 28) & 0xF);
    }

    /** The part field. */
    public int part() {
        return (int) ((value >> 12) & 0xFFFF);
    }

    /** The JEDEC manufacturer field. */
    public int manufacturer() {
        return (int) ((value >> 1) & 0x7FF);
    }

    /** Who made the part, as a name. */
    public String manufacturerName() {
        return manufacturerName(manufacturer());
    }

    /** Whether bit zero is set, as the standard requires. */
    public boolean hasValidLsb() {
        return (value & 1) != 0;
    }

    /** All ones is an open chain, not a part. */
    public boolean isFloating() {
        return value == ALL_ONES;
    }

    /**
     * Whether this could be a real device rather than a wiring fault.
     *
     * <p>A manufacturer field of all ones is the same floating line seen one field at a time, and
     * zero is what a chain held low reads as.
     */
    public boolean isPlausible() {
        if (isFloating() || value == 0 || !hasValidLsb()) {
            return false;
        }
        return manufacturer() != 0x000 && manufacturer() != 0x7FF;
    }

    /** The same idcode with the stepping nibble cleared, for comparison. */
    public IdCode withoutVersion() {
        return new IdCode(value & 0x0FFF_FFFFL);
    }

    /** Whether two idcodes name the same part, whatever the stepping. */
    public boolean sameDevice(IdCode other) {
        return withoutVersion().value == other.withoutVersion().value;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "0x%08x", value);
    }

    private static long hex(String cleaned, String original) {
        String digits = cleaned;
        boolean negative = false;
        if (digits.startsWith("+") || digits.startsWith("-")) {
            negative = digits.startsWith("-");
            digits = digits.substring(1);
        }
        if (digits.startsWith("0x")) {
            digits = digits.substring(2);
        }
        if (digits.isEmpty() || digits.length() > 15 || !digits.chars().allMatch(IdCode::isHexDigit)) {
            throw new ValidationError("cannot read idcode " + Repr.of(original), "idcode");
        }
        long value = Long.parseLong(digits, 16);
        return negative ? -value : value;
    }

    private static boolean isHexDigit(int ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f');
    }
}
