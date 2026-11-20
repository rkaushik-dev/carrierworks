package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.util.Repr;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Names and serials, and the rules each of them has to keep.
 *
 * <p>Adapters get whatever serial FTDI burned into them, which is opaque but unique. Designs get a
 * name from whoever wrote the RTL, and those arrive with spaces, capitals and the occasional slash
 * in them. Teams, channels and racks get a slug. Asset tags are {@link AssetTag}.
 */
public final class Ids {

    private static final Pattern SERIAL = Pattern.compile("[A-Z0-9]{6,16}");
    private static final Pattern DESIGN = Pattern.compile("[a-z][a-z0-9_]{2,63}");
    // One character is allowed. Board revisions are single letters and refusing rev a would mean
    // spelling it rev-a everywhere it is printed.
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern DESIGN_SEPARATORS = Pattern.compile("[\\s\\-/.]+");
    private static final Pattern REPEATED_UNDERSCORES = Pattern.compile("_{2,}");
    private static final Pattern SLUG_SEPARATORS = Pattern.compile("[\\s_]+");
    private static final Pattern REPEATED_DASHES = Pattern.compile("-{2,}");

    private Ids() {}

    /**
     * A programmer serial, upper cased and checked for shape.
     *
     * <p>FTDI and Digilent both burn alphanumeric strings and both are inconsistent about case when
     * they report them, so the bench stores one form.
     */
    public static String adapterSerial(String text) {
        String cleaned = text.strip().toUpperCase(Locale.ROOT).replace(" ", "");
        if (!SERIAL.matcher(cleaned).matches()) {
            throw new ValidationError("not an adapter serial " + Repr.of(text), "serial");
        }
        return cleaned;
    }

    /**
     * A design name as the build tools would accept it.
     *
     * <p>Spaces and dashes become underscores because that is what the synthesis tool does anyway,
     * and having the bench disagree with the tool about the name of a design is a whole afternoon of
     * confusion.
     */
    public static String designName(String text) {
        String cleaned = text.strip().toLowerCase(Locale.ROOT);
        cleaned = DESIGN_SEPARATORS.matcher(cleaned).replaceAll("_");
        cleaned = strip(REPEATED_UNDERSCORES.matcher(cleaned).replaceAll("_"), '_');
        if (!DESIGN.matcher(cleaned).matches()) {
            throw new ValidationError("not a usable design name " + Repr.of(text), "design");
        }
        return cleaned;
    }

    /** A general identifier for teams, channels, racks and the like. */
    public static String slug(String text) {
        String cleaned =
                SLUG_SEPARATORS.matcher(text.strip().toLowerCase(Locale.ROOT)).replaceAll("-");
        cleaned = strip(REPEATED_DASHES.matcher(cleaned).replaceAll("-"), '-');
        if (!SLUG.matcher(cleaned).matches()) {
            throw new ValidationError("not a usable identifier " + Repr.of(text), "slug");
        }
        return cleaned;
    }

    private static String strip(String text, char ch) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == ch) {
            start++;
        }
        while (end > start && text.charAt(end - 1) == ch) {
            end--;
        }
        return text.substring(start, end);
    }
}
