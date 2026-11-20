package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.util.Numbers;
import io.github.rkaushikdev.carrierworks.util.Repr;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Volts and amps, read however the lab wrote them down.
 *
 * <p>Everything is millivolts and milliamps as whole numbers. A rail that reads 1.7994 volts is
 * 1799 millivolts and the fourth decimal place is the meter's opinion, not the board's.
 *
 * <p>The lab writes rails as {@code 3v3}, the schematic writes {@code 3.3V} and the supply web page
 * writes {@code 3.30 V}. They all mean the same rail and all three arrive here. Text is always volts
 * and a plain integer is always millivolts, which is the one rule that keeps a config file from
 * asking for a three thousand volt rail.
 */
public final class Electrical {

    private static final Pattern VOLTAGE =
            Pattern.compile("(?:(?<whole>\\d+)v(?<fraction>\\d{1,3})?|(?<decimal>\\d+(?:\\.\\d{1,3})?)\\s*v?)");
    private static final Pattern CURRENT = Pattern.compile("(?<value>\\d+(?:\\.\\d{1,3})?)\\s*(?<unit>ma|a)?");

    private Electrical() {}

    /** A rail voltage written as volts, in millivolts. */
    public static int parseVoltage(String text) {
        String cleaned = text.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        if (cleaned.isEmpty()) {
            throw new ValidationError("empty voltage", "voltage");
        }
        Matcher match = VOLTAGE.matcher(cleaned);
        if (!match.matches()) {
            throw new ValidationError("cannot read a voltage from " + Repr.of(text), "voltage");
        }
        if (match.group("whole") != null) {
            String fraction = match.group("fraction") == null ? "" : match.group("fraction");
            return Integer.parseInt(match.group("whole")) * 1000 + thousandths(fraction);
        }
        return volts(match.group("decimal"));
    }

    /** A rail voltage that is already millivolts. */
    public static int parseVoltage(int millivolts) {
        if (millivolts < 0) {
            throw new ValidationError("negative voltage", "voltage", Details.of("given", millivolts));
        }
        return millivolts;
    }

    /** A current written as milliamps or amps ({@code 500}, {@code 500ma}, {@code 1.5A}), in milliamps. */
    public static int parseCurrent(String text) {
        String cleaned = text.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher match = CURRENT.matcher(cleaned);
        if (!match.matches()) {
            throw new ValidationError("cannot read a current from " + Repr.of(text), "current");
        }
        String unit = match.group("unit") == null ? "ma" : match.group("unit");
        int thousandths = volts(match.group("value"));
        if (unit.equals("a")) {
            return thousandths;
        }
        if (thousandths % 1000 != 0) {
            throw new ValidationError("milliamps are whole numbers", "current", Details.of("given", text));
        }
        return thousandths / 1000;
    }

    /** A current that is already milliamps. */
    public static int parseCurrent(int milliamps) {
        if (milliamps < 0) {
            throw new ValidationError("negative current", "current", Details.of("given", milliamps));
        }
        return milliamps;
    }

    /** Power, rounded to the nearest whole milliwatt, halves to even. */
    public static int milliwatts(int millivolts, int milliamps) {
        if (millivolts < 0 || milliamps < 0) {
            throw new ValidationError("power is not defined for negative readings", "power");
        }
        return Math.toIntExact(Numbers.round((double) millivolts * milliamps / 1000));
    }

    /** {@code 12.34} as 12340: a decimal with up to three places, scaled by a thousand. */
    private static int volts(String decimal) {
        int point = decimal.indexOf('.');
        if (point < 0) {
            return Integer.parseInt(decimal) * 1000;
        }
        return Integer.parseInt(decimal.substring(0, point)) * 1000 + thousandths(decimal.substring(point + 1));
    }

    private static int thousandths(String fraction) {
        return Integer.parseInt((fraction + "000").substring(0, 3));
    }
}
