package io.github.rkaushikdev.carrierworks.artifacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fields of one record, read back out of a parsed JSON object.
 *
 * <p>The metadata files beside an image are written by this package and nothing else, so anything
 * in them that is missing, of the wrong type or not a field of the record means the file is not
 * one of them. That is refused rather than guessed at, and refused as an {@link
 * IllegalArgumentException} the way malformed JSON text is, because it is a damaged file rather
 * than a request the bench can say no to.
 */
final class JsonFields {

    private final String what;
    private final Map<?, ?> fields;

    private JsonFields(String what, Map<?, ?> fields) {
        this.what = what;
        this.fields = fields;
    }

    /** The fields of a parsed value, which has to be an object holding only the names given. */
    static JsonFields of(String what, Object parsed, Set<String> known) {
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(what + " is not a JSON object");
        }
        for (Object key : map.keySet()) {
            if (!known.contains(String.valueOf(key))) {
                throw new IllegalArgumentException(what + " has no field " + key);
            }
        }
        return new JsonFields(what, map);
    }

    /** A text field that has to be there. */
    String text(String key) {
        return text(key, null);
    }

    /** A text field, or the fallback when it is left out. */
    String text(String key, String fallback) {
        Object value = value(key, fallback);
        if (!(value instanceof String text)) {
            throw wrong(key, "text");
        }
        return text;
    }

    /** A true or false field, or the fallback when it is left out. */
    boolean flag(String key, boolean fallback) {
        Object value = value(key, fallback);
        if (!(value instanceof Boolean flag)) {
            throw wrong(key, "true or false");
        }
        return flag;
    }

    /** A whole number field that has to be there. */
    long whole(String key) {
        Object value = value(key, null);
        if (!(value instanceof Long number)) {
            throw wrong(key, "a whole number");
        }
        return number;
    }

    /** A list of text, empty when it is left out. */
    List<String> texts(String key) {
        Object value = value(key, List.of());
        if (!(value instanceof List<?> items)) {
            throw wrong(key, "a list");
        }
        List<String> found = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String text)) {
                throw wrong(key, "a list of text");
            }
            found.add(text);
        }
        return found;
    }

    private Object value(String key, Object fallback) {
        if (fields.containsKey(key)) {
            return fields.get(key);
        }
        if (fallback == null) {
            throw new IllegalArgumentException(what + " is missing its " + key);
        }
        return fallback;
    }

    private IllegalArgumentException wrong(String key, String kind) {
        return new IllegalArgumentException(what + " field " + key + " is not " + kind);
    }
}
