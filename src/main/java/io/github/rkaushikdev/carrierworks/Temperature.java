package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.util.Numbers;
import io.github.rkaushikdev.carrierworks.util.Repr;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Die temperature, in the units the silicon actually reports it in.
 *
 * <p>The system monitor on a 7 series part hands back a twelve bit code, not a temperature.
 * Turning one into the other is a fixed affine formula from the user guide, and it is here rather
 * than in the driver because the same conversion has to run over stored codes when somebody asks
 * what the board was doing an hour ago.
 *
 * <p>Everything is millidegrees celsius. The sensor resolution is about an eighth of a degree, so
 * tenths lose real information and floats invite a comparison that is true on one machine and
 * false on another.
 */
public final class Temperature {

    private static final Pattern TEMPERATURE =
            Pattern.compile("(?<sign>-?)(?<whole>\\d+)(?:\\.(?<fraction>\\d{1,3}))?\\s*(?:c|degc)?");

    // The 7 series system monitor transfer function, held as integers: the reading in
    // millidegrees is code * 503975 / 4096 - 273150.
    private static final long XADC_SCALE_NUMERATOR = 503_975;
    private static final long XADC_SCALE_DENOMINATOR = 4096;
    private static final long XADC_OFFSET_MC = 273_150;
    private static final int XADC_FULL_SCALE = 0xFFF;

    private Temperature() {}

    /** A temperature in millidegrees, from whatever wrote it down. */
    public static int parse(String text) {
        String cleaned = text.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        cleaned = stripDegreeSigns(cleaned);
        if (cleaned.isEmpty()) {
            throw new ValidationError("empty temperature", "temperature");
        }
        Matcher match = TEMPERATURE.matcher(cleaned);
        if (!match.matches()) {
            throw new ValidationError("cannot read a temperature from " + Repr.of(text), "temperature");
        }
        String fraction = match.group("fraction") == null ? "" : match.group("fraction");
        int millidegrees =
                Integer.parseInt(match.group("whole")) * 1000 + Integer.parseInt((fraction + "000").substring(0, 3));
        return match.group("sign").isEmpty() ? millidegrees : -millidegrees;
    }

    /** Whole degrees as millidegrees. */
    public static int parse(int degrees) {
        return Math.multiplyExact(degrees, 1000);
    }

    /** Degrees as millidegrees, rounded to the nearest one. */
    public static int parse(double degrees) {
        return Math.toIntExact(Numbers.round(degrees * 1000));
    }

    /** Back to one decimal place, which is what a person wants to read: {@code 62.5C}. */
    public static String render(int millidegrees) {
        return Numbers.fixed(millidegrees / 1000.0, 1) + "C";
    }

    /** A raw system monitor code as millidegrees celsius. */
    public static int fromXadcCode(int code) {
        if (code < 0 || code > XADC_FULL_SCALE) {
            throw new ValidationError("system monitor codes are twelve bits", "code", Details.of("given", code));
        }
        long scaled = Math.floorDiv(code * XADC_SCALE_NUMERATOR, XADC_SCALE_DENOMINATOR);
        return (int) (scaled - XADC_OFFSET_MC);
    }

    /** The code the part would report for a temperature, for fakes and tests. */
    public static int toXadcCode(int millidegrees) {
        long scaled = (millidegrees + XADC_OFFSET_MC) * XADC_SCALE_DENOMINATOR;
        long code = Numbers.round((double) scaled / XADC_SCALE_NUMERATOR);
        if (code < 0 || code > XADC_FULL_SCALE) {
            throw new ValidationError(
                    "temperature is outside what the sensor can report",
                    "temperature",
                    Details.of("given", millidegrees));
        }
        return (int) code;
    }

    private static String stripDegreeSigns(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '°') {
            end--;
        }
        return text.substring(0, end);
    }
}
