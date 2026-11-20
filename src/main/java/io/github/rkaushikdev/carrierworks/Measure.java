package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.util.Numbers;
import io.github.rkaushikdev.carrierworks.util.Repr;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The units the bench argues in: sizes and timing figures.
 *
 * <p>Sizes are binary and always in bytes, because a flash sector is 65536 bytes and nobody in the
 * lab has ever meant 65000. Times are picoseconds, because the timing reports are in nanoseconds to
 * three decimal places and floating point addition over four thousand of those is how a slack
 * total drifts. Frequencies are {@link Frequency}.
 *
 * <p>Everything is integer inside. Formatting to something readable happens at the edges and never
 * round trips through a float on the way back.
 */
public final class Measure {

    private static final Map<String, Long> SIZE_UNITS = Map.ofEntries(
            Map.entry("", 1L),
            Map.entry("b", 1L),
            Map.entry("byte", 1L),
            Map.entry("bytes", 1L),
            Map.entry("k", 1024L),
            Map.entry("kb", 1024L),
            Map.entry("kib", 1024L),
            Map.entry("m", 1024L * 1024),
            Map.entry("mb", 1024L * 1024),
            Map.entry("mib", 1024L * 1024),
            Map.entry("g", 1024L * 1024 * 1024),
            Map.entry("gb", 1024L * 1024 * 1024),
            Map.entry("gib", 1024L * 1024 * 1024));

    private static final Pattern SIZE = Pattern.compile("(?<value>\\d+(?:\\.\\d+)?)\\s*(?<unit>[a-z]*)");
    private static final Pattern NANOSECONDS = Pattern.compile("\\d+(\\.\\d{1,3})?");

    private Measure() {}

    /** {@code count} kibibytes, in bytes. */
    public static long kib(long count) {
        return count * 1024;
    }

    /** {@code count} mebibytes, in bytes. */
    public static long mib(long count) {
        return count * 1024 * 1024;
    }

    /**
     * Read a size written the way a datasheet writes it, in bytes.
     *
     * <p>A bare number is bytes. Decimal values are allowed only where they land on a whole byte,
     * so {@code 1.5 MiB} is fine and {@code 1.5 B} is not.
     */
    public static long parseSize(String text) {
        String cleaned = text.strip().toLowerCase(Locale.ROOT);
        Matcher match = SIZE.matcher(cleaned);
        if (!match.matches()) {
            throw new ValidationError("cannot read a size from " + Repr.of(text), "size");
        }
        String unit = match.group("unit");
        Long scale = SIZE_UNITS.get(unit);
        if (scale == null) {
            throw new ValidationError("unknown size unit " + Repr.of(unit), "size");
        }
        String raw = match.group("value");
        int point = raw.indexOf('.');
        if (point < 0) {
            return new BigInteger(raw).multiply(BigInteger.valueOf(scale)).longValueExact();
        }
        BigInteger whole = new BigInteger(raw.substring(0, point));
        String fractionText = raw.substring(point + 1);
        BigInteger fraction = new BigInteger(fractionText);
        BigInteger denominator = BigInteger.TEN.pow(fractionText.length());
        BigInteger bigScale = BigInteger.valueOf(scale);
        BigInteger scaled =
                whole.multiply(bigScale).add(fraction.multiply(bigScale).divide(denominator));
        BigInteger exact = whole.multiply(bigScale).multiply(denominator).add(fraction.multiply(bigScale));
        if (exact.mod(denominator).signum() != 0) {
            throw new ValidationError("size is not a whole number of bytes", "size", Details.of("given", text));
        }
        return scaled.longValueExact();
    }

    /** Bytes back to something a person reads without counting zeros. */
    public static String formatSize(long size) {
        if (size < 0) {
            throw new ValidationError("negative size", "size", Details.of("given", size));
        }
        String[] units = {"GiB", "MiB", "KiB"};
        long[] scales = {1024L * 1024 * 1024, 1024L * 1024, 1024L};
        for (int index = 0; index < units.length; index++) {
            long scale = scales[index];
            if (size < scale) {
                continue;
            }
            if (size % scale == 0) {
                return (size / scale) + " " + units[index];
            }
            return Numbers.fixed((double) size / scale, 1) + " " + units[index];
        }
        return size + " B";
    }

    /**
     * A nanosecond figure out of a report, as whole picoseconds.
     *
     * <p>Timing reports print three decimal places, so a value with more than that is either a
     * different tool or a typo. Both are worth refusing.
     */
    public static long picosecondsFromNs(String text) {
        return picoseconds(text.strip(), text);
    }

    /** The same for a figure that arrived as a number, pinned to six places first. */
    public static long picosecondsFromNs(double nanoseconds) {
        String raw = stripZeros(Numbers.fixed(nanoseconds, 6));
        return picoseconds(raw, nanoseconds);
    }

    /** Picoseconds back out in the three decimal form the reports use. */
    public static String renderNs(long picoseconds) {
        String sign = picoseconds < 0 ? "-" : "";
        long value = Math.abs(picoseconds);
        return String.format(Locale.ROOT, "%s%d.%03d", sign, value / 1000, value % 1000);
    }

    private static long picoseconds(String raw, Object given) {
        if (raw.isEmpty()) {
            throw new ValidationError("empty timing value", "ns");
        }
        boolean negative = raw.startsWith("-");
        if (raw.charAt(0) == '+' || raw.charAt(0) == '-') {
            raw = raw.substring(1);
        }
        if (!NANOSECONDS.matcher(raw).matches()) {
            throw new ValidationError("cannot read nanoseconds from " + Repr.of(given), "ns");
        }
        int point = raw.indexOf('.');
        String whole = point < 0 ? raw : raw.substring(0, point);
        String fraction = point < 0 ? "" : raw.substring(point + 1);
        String padded = (fraction + "000").substring(0, 3);
        long picoseconds = Long.parseLong(whole) * 1000 + Long.parseLong(padded);
        return negative ? -picoseconds : picoseconds;
    }

    private static String stripZeros(String fixed) {
        int end = fixed.length();
        while (end > 0 && fixed.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && fixed.charAt(end - 1) == '.') {
            end--;
        }
        return fixed.substring(0, end);
    }
}
