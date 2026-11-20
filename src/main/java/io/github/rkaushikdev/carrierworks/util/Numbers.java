package io.github.rkaushikdev.carrierworks.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rounding and printing numbers the way the bench always has.
 *
 * <p>Two rules, both about the exact binary value rather than the decimal one a person reads. A
 * figure printed to a fixed number of places is rounded half to even on the value the double
 * actually holds, so {@code 0.25} prints to one place as {@code 0.2} and {@code 0.15}, which is
 * really a hair under, prints as {@code 0.1}. {@link String#format} rounds the decimal text half
 * up and disagrees with both. And a whole-number rounding goes half to even as well, so a reading
 * exactly between two milliwatts lands on the even one every time.
 */
public final class Numbers {

    private Numbers() {}

    /** A value printed to {@code places} decimal places, half to even on the exact value. */
    public static String fixed(double value, int places) {
        if (places < 0) {
            throw new IllegalArgumentException("places must not be negative");
        }
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        boolean negative = Double.doubleToRawLongBits(value) < 0;
        String digits = new BigDecimal(Math.abs(value))
                .setScale(places, RoundingMode.HALF_EVEN)
                .toPlainString();
        return negative ? "-" + digits : digits;
    }

    /** The nearest whole number, halves going to the even neighbour. */
    public static long round(double value) {
        if (!Double.isFinite(value)) {
            throw new ArithmeticException("cannot round " + value + " to a whole number");
        }
        return (long) Math.rint(value);
    }

    /** The nearest multiple of ten to the minus {@code places}, halves going to even. */
    public static double round(double value, int places) {
        if (!Double.isFinite(value)) {
            return value;
        }
        return new BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN).doubleValue();
    }

    /**
     * The shortest text that reads back as exactly this double.
     *
     * <p>Plain notation from a ten thousandth up to ten quadrillion, and always with a decimal
     * point, so {@code 3.0} stays {@code 3.0}. Outside that range it is scientific with a signed,
     * two digit exponent: {@code 1e+16}, {@code 1.5e-05}.
     */
    public static String shortest(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        String sign = Double.doubleToRawLongBits(value) < 0 ? "-" : "";
        if (value == 0.0) {
            return sign + "0.0";
        }
        BigDecimal decimal = new BigDecimal(Double.toString(Math.abs(value))).stripTrailingZeros();
        String digits = decimal.unscaledValue().toString();
        int exponent = digits.length() - 1 - decimal.scale();
        if (exponent < -4 || exponent >= 16) {
            StringBuilder out = new StringBuilder(sign).append(digits.charAt(0));
            if (digits.length() > 1) {
                out.append('.').append(digits, 1, digits.length());
            }
            out.append('e').append(exponent < 0 ? '-' : '+');
            int magnitude = Math.abs(exponent);
            if (magnitude < 10) {
                out.append('0');
            }
            return out.append(magnitude).toString();
        }
        if (exponent < 0) {
            return sign + "0." + "0".repeat(-exponent - 1) + digits;
        }
        if (digits.length() <= exponent + 1) {
            return sign + digits + "0".repeat(exponent + 1 - digits.length()) + ".0";
        }
        return sign + digits.substring(0, exponent + 1) + "." + digits.substring(exponent + 1);
    }
}
