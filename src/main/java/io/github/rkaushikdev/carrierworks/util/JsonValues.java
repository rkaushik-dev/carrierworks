package io.github.rkaushikdev.carrierworks.util;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The forgiving reads a hand-edited JSON document needs, over the values {@link Json#parse} hands
 * back.
 *
 * <p>A file that people edit by hand holds a number where a string was meant, a string where a
 * number was meant, and an empty string or a {@code null} where a field was meant to be left out.
 * These read such a value the one lenient way the bench reads it: as text, as present or absent,
 * and as a whole number.
 */
public final class JsonValues {

    private static final Pattern WHOLE = Pattern.compile("[+-]?\\d+(?:_\\d+)*");

    private JsonValues() {}

    /**
     * The value as text: a string as itself, anything else in its quoted form ({@code None}, {@code
     * True}, {@code 1800}, {@code ['a', 1]}).
     */
    public static String text(Object value) {
        return value instanceof String string ? string : Repr.of(value);
    }

    /**
     * Whether a value counts as given: not {@code null}, not {@code false}, not zero, and not an
     * empty string, list or object.
     */
    public static boolean truthy(Object value) {
        return switch (value) {
            case null -> false;
            case Boolean flag -> flag;
            case Double number -> number != 0.0;
            case Float number -> number != 0.0f;
            case Number number -> number.longValue() != 0;
            case CharSequence string -> !string.isEmpty();
            case Collection<?> items -> !items.isEmpty();
            case Map<?, ?> members -> !members.isEmpty();
            default -> true;
        };
    }

    /**
     * The value as a whole number.
     *
     * <p>A flag is one or zero, a fraction is cut toward zero, and text is read as decimal digits
     * with an optional sign, surrounding white space and underscores between digits.
     *
     * @throws NumberFormatException when the text does not hold a whole number that fits a long
     * @throws IllegalArgumentException when the value is missing, not finite, or not a number at all
     */
    public static long whole(Object value) {
        return switch (value) {
            case Boolean flag -> flag ? 1 : 0;
            case Double number -> truncated(number);
            case Float number -> truncated(number.doubleValue());
            case Number number -> number.longValue();
            case String string -> parsed(string);
            case null, default -> throw new IllegalArgumentException("not a whole number: " + Repr.of(value));
        };
    }

    /** A member of an object, or the fallback when the object has no such member at all. */
    public static Object get(Map<?, ?> object, String key, Object fallback) {
        return object.containsKey(key) ? object.get(key) : fallback;
    }

    private static long truncated(double number) {
        if (!Double.isFinite(number)) {
            throw new IllegalArgumentException("not a whole number: " + Numbers.shortest(number));
        }
        return (long) number;
    }

    private static long parsed(String string) {
        String cleaned = string.strip();
        if (!WHOLE.matcher(cleaned).matches()) {
            throw new NumberFormatException("not a whole number: " + Repr.of(string));
        }
        return Long.parseLong(cleaned.replace("_", ""));
    }
}
