package io.github.rkaushikdev.carrierworks.util;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * The quoted form a message shows a value in.
 *
 * <p>Error messages and error details on the bench have always quoted what they were given the
 * same way: a string in single quotes with its awkward characters escaped, a number as itself, a
 * missing value as {@code None} and a flag as {@code True} or {@code False}. Operators grep the job
 * logs for those lines, so the form is kept exactly rather than left to {@code toString}.
 */
public final class Repr {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Repr() {}

    /** The quoted form of one value. */
    public static String of(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(Repr::of).orElse("None");
        }
        if (value instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        if (value instanceof CharSequence text) {
            return string(text.toString());
        }
        if (value instanceof Double number) {
            return Numbers.shortest(number);
        }
        if (value instanceof Float number) {
            return Numbers.shortest(number.doubleValue());
        }
        if (value instanceof Map<?, ?> map) {
            return mapping(map);
        }
        if (value instanceof Collection<?> items) {
            return sequence(items.iterator(), "[", "]");
        }
        return value.toString();
    }

    /** A string quoted the way the bench quotes one: single quotes unless it holds one. */
    public static String string(String text) {
        char quote = text.indexOf('\'') >= 0 && text.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder out = new StringBuilder(text.length() + 2).append(quote);
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == quote || ch == '\\') {
                out.append('\\').append(ch);
            } else if (ch == '\n') {
                out.append("\\n");
            } else if (ch == '\r') {
                out.append("\\r");
            } else if (ch == '\t') {
                out.append("\\t");
            } else if (ch < 0x20 || (ch >= 0x7f && ch < 0xa0)) {
                out.append("\\x").append(HEX[(ch >> 4) & 0xF]).append(HEX[ch & 0xF]);
            } else {
                out.append(ch);
            }
        }
        return out.append(quote).toString();
    }

    private static String mapping(Map<?, ?> map) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(", ");
            }
            first = false;
            out.append(of(entry.getKey())).append(": ").append(of(entry.getValue()));
        }
        return out.append('}').toString();
    }

    private static String sequence(Iterator<?> items, String open, String close) {
        StringBuilder out = new StringBuilder(open);
        while (items.hasNext()) {
            out.append(of(items.next()));
            if (items.hasNext()) {
                out.append(", ");
            }
        }
        return out.append(close).toString();
    }
}
