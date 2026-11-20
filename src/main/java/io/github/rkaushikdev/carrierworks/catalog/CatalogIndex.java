package io.github.rkaushikdev.carrierworks.catalog;

import io.github.rkaushikdev.carrierworks.CatalogError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.NotFound;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * An immutable catalog with one name and any number of other keys.
 *
 * <p>Devices, flash parts, cards and adapters were each a list, a map built by a loop, a lookup
 * that returns nothing when it misses, and a lookup that raises. This is that shape written once.
 * It takes the entries and the function that names them, builds whatever secondary indexes are
 * asked for, and refuses at build time if two entries claim one name or one index value. That last
 * part is the reason this is worth having: a duplicate idcode in the device catalog is a bug that
 * would otherwise show up as a chain reading back as the wrong part.
 *
 * @param <T> what the catalog holds
 */
public final class CatalogIndex<T> {

    private final String kind;
    private final List<T> entries;
    private final Map<String, T> primary;
    private final Map<String, Map<Object, T>> secondary;

    /** A catalog with only the name to find entries by. */
    public CatalogIndex(String kind, List<T> entries, Function<? super T, String> key) {
        this(kind, entries, key, Map.of());
    }

    /**
     * A catalog with secondary indexes, each named and each with the function that gives an entry's
     * value in it.
     */
    public CatalogIndex(
            String kind,
            List<T> entries,
            Function<? super T, String> key,
            Map<String, Function<? super T, ?>> secondary) {
        this.kind = kind;
        this.entries = List.copyOf(entries);
        Map<String, T> names = new HashMap<>();
        for (T entry : this.entries) {
            String name = key.apply(entry);
            if (names.containsKey(name)) {
                throw new CatalogError("two entries claim the name " + name, Details.of("catalog", kind));
            }
            names.put(name, entry);
        }
        this.primary = Collections.unmodifiableMap(names);
        Map<String, Map<Object, T>> indexes = new LinkedHashMap<>();
        secondary.forEach((indexName, indexKey) -> {
            Map<Object, T> built = new HashMap<>();
            for (T entry : this.entries) {
                Object value = indexKey.apply(entry);
                if (built.containsKey(value)) {
                    throw new CatalogError(
                            "two entries share a " + indexName,
                            Details.of("catalog", kind, "value", String.valueOf(value)));
                }
                built.put(value, entry);
            }
            indexes.put(indexName, Collections.unmodifiableMap(built));
        });
        this.secondary = Collections.unmodifiableMap(indexes);
    }

    /** How names are compared: trimmed and lower cased. */
    public static String normalise(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    /** What the catalog holds, as a not found names it. */
    public String kind() {
        return kind;
    }

    /** The entry with that name, or nothing when the lab does not stock it. */
    public Optional<T> lookup(String name) {
        return Optional.ofNullable(primary.get(normalise(name)));
    }

    /** The entry with that name, or a not found naming what was asked for. */
    public T require(String name) {
        return lookup(name).orElseThrow(() -> new NotFound(kind, normalise(name)));
    }

    /** The entry with that value in a secondary index, or nothing. */
    public Optional<T> find(String index, Object value) {
        Map<Object, T> built = secondary.get(index);
        if (built == null) {
            throw new CatalogError("no " + index + " index on this catalog", Details.of("catalog", kind));
        }
        return Optional.ofNullable(built.get(value));
    }

    /** The entry with that value in a secondary index, or a not found naming the value. */
    public T requireBy(String index, Object value) {
        return requireBy(index, value, String.valueOf(value));
    }

    /** The same, naming the value the way the caller wants it shown. */
    public T requireBy(String index, Object value, String shown) {
        return find(index, value).orElseThrow(() -> new NotFound(kind + " " + index, shown));
    }

    /** Every entry that passes the filter, in catalog order. */
    public List<T> where(Predicate<? super T> predicate) {
        return entries.stream().filter(predicate).toList();
    }

    /** Every entry, in catalog order. */
    public List<T> all() {
        return entries;
    }

    /** Every name, sorted. */
    public List<String> names() {
        return primary.keySet().stream().sorted().toList();
    }

    /** How many entries there are. */
    public int size() {
        return entries.size();
    }

    /** Whether something is a name in this catalog; anything that is not a string never is. */
    public boolean contains(Object name) {
        return name instanceof String text && primary.containsKey(normalise(text));
    }
}
