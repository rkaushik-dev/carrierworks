package io.github.rkaushikdev.carrierworks.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * JSON in one fixed text form, and back again.
 *
 * <p>The journal, the wear ledger, the provenance files, the bench export and the bundle manifests
 * are all JSON that lives on disk for years, and the bundle manifest is hashed. So the writer here
 * produces one canonical layout whose bytes never vary: {@code ", "} and {@code ": "} between items on
 * one line, {@code ","} and a newline per item when indented, every character outside printable
 * ASCII escaped as {@code \u0000}, and floats in their shortest form. Keys keep the order they were
 * put in unless the writer is told to sort them.
 *
 * <p>Reading goes through Jackson, into plain maps (insertion ordered), lists, strings, booleans,
 * {@code null}, {@code Long} for whole numbers that fit and {@code Double} for the rest.
 */
public final class Json {

    private static final ObjectMapper READER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .build();

    private static final Writer COMPACT = new Writer(-1, false, null);

    private Json() {}

    /** Text that did not read as JSON. */
    public static final class MalformedJsonException extends IllegalArgumentException {

        MalformedJsonException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** One value on one line, keys in the order they were put in. */
    public static String write(Object value) {
        return COMPACT.write(value);
    }

    /** A writer with nothing set: one line, keys unsorted, nothing but JSON values accepted. */
    public static Writer writer() {
        return COMPACT;
    }

    /**
     * The text form the bench prints a value in when it is not JSON of itself.
     *
     * <p>A timestamp prints as {@code 2026-01-15 02:00:00+00:00}, a date as {@code 2026-01-15},
     * anything else as its {@code toString}.
     */
    public static Object text(Object value) {
        if (value instanceof Instant instant) {
            return Times.text(instant);
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof Path path) {
            return path.toString();
        }
        return value.toString();
    }

    /**
     * Read one JSON document.
     *
     * @throws MalformedJsonException when the text is not exactly one JSON value
     */
    public static Object parse(String text) {
        try {
            return READER.readValue(text, Object.class);
        } catch (JsonProcessingException failure) {
            throw new MalformedJsonException(failure.getOriginalMessage(), failure);
        }
    }

    /**
     * How a value is laid out: indentation, key order, and what to do with anything that is not a
     * JSON value of itself.
     */
    public static final class Writer {

        private final int indent;
        private final boolean sortKeys;
        private final Function<Object, Object> fallback;

        private Writer(int indent, boolean sortKeys, Function<Object, Object> fallback) {
            this.indent = indent;
            this.sortKeys = sortKeys;
            this.fallback = fallback;
        }

        /** One item per line, each level indented this many spaces further. */
        public Writer indent(int spaces) {
            if (spaces < 0) {
                throw new IllegalArgumentException("indent must not be negative");
            }
            return new Writer(spaces, sortKeys, fallback);
        }

        /** Keys in sorted order rather than the order they were put in. */
        public Writer sortKeys() {
            return new Writer(indent, true, fallback);
        }

        /** Anything that is not a JSON value is first turned into one by this. */
        public Writer fallback(Function<Object, Object> convert) {
            return new Writer(indent, sortKeys, convert);
        }

        /** Anything that is not a JSON value is written as its {@link Json#text} form. */
        public Writer textFallback() {
            return fallback(Json::text);
        }

        /** The value as JSON text. */
        public String write(Object value) {
            StringBuilder out = new StringBuilder();
            emit(out, value, 0);
            return out.toString();
        }

        private void emit(StringBuilder out, Object value, int depth) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof Optional<?> optional) {
                emit(out, optional.orElse(null), depth);
            } else if (value instanceof CharSequence text) {
                quote(out, text.toString());
            } else if (value instanceof Boolean flag) {
                out.append(flag ? "true" : "false");
            } else if (value instanceof Integer
                    || value instanceof Long
                    || value instanceof Short
                    || value instanceof Byte
                    || value instanceof BigInteger) {
                out.append(value);
            } else if (value instanceof Double || value instanceof Float) {
                out.append(number(((Number) value).doubleValue()));
            } else if (value instanceof BigDecimal decimal) {
                out.append(number(decimal.doubleValue()));
            } else if (value instanceof Map<?, ?> map) {
                object(out, map, depth);
            } else if (value instanceof Collection<?> items) {
                array(out, new ArrayList<>(items), depth);
            } else if (value instanceof Object[] items) {
                array(out, Arrays.asList(items), depth);
            } else if (fallback != null) {
                emit(out, fallback.apply(value), depth);
            } else {
                throw new IllegalArgumentException("a " + value.getClass().getSimpleName() + " is not a JSON value");
            }
        }

        private void object(StringBuilder out, Map<?, ?> map, int depth) {
            if (map.isEmpty()) {
                out.append("{}");
                return;
            }
            List<Member> members = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                members.add(new Member(key(entry.getKey()), entry.getValue()));
            }
            if (sortKeys) {
                members.sort(Comparator.comparing(Member::name));
            }
            out.append('{');
            for (int index = 0; index < members.size(); index++) {
                separate(out, index, depth + 1);
                quote(out, members.get(index).name());
                out.append(": ");
                emit(out, members.get(index).value(), depth + 1);
            }
            close(out, depth);
            out.append('}');
        }

        private void array(StringBuilder out, List<?> items, int depth) {
            if (items.isEmpty()) {
                out.append("[]");
                return;
            }
            out.append('[');
            for (int index = 0; index < items.size(); index++) {
                separate(out, index, depth + 1);
                emit(out, items.get(index), depth + 1);
            }
            close(out, depth);
            out.append(']');
        }

        private void separate(StringBuilder out, int index, int depth) {
            if (indent < 0) {
                if (index > 0) {
                    out.append(", ");
                }
                return;
            }
            if (index > 0) {
                out.append(',');
            }
            out.append('\n').append(" ".repeat(indent * depth));
        }

        private void close(StringBuilder out, int depth) {
            if (indent >= 0) {
                out.append('\n').append(" ".repeat(indent * depth));
            }
        }

        private static String key(Object key) {
            if (key == null) {
                return "null";
            }
            if (key instanceof Boolean flag) {
                return flag ? "true" : "false";
            }
            if (key instanceof Double || key instanceof Float) {
                return number(((Number) key).doubleValue());
            }
            return key.toString();
        }
    }

    /** One key and its value, which may be null where a map entry may not. */
    private record Member(String name, Object value) {}

    private static String number(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        return Numbers.shortest(value);
    }

    private static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (ch < 0x20 || ch > 0x7e) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        out.append('"');
    }
}
