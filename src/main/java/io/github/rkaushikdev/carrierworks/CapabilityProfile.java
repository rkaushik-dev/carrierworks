package io.github.rkaushikdev.carrierworks;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything true about one board, in the form requirements ask about.
 *
 * <p>Tokens are the capabilities a board or its cards bring, numbers are counts (a missing count
 * is none of them rather than missing), and attributes are named values such as the device family.
 *
 * @param tokens what the board can do
 * @param numbers how many of each countable thing it has
 * @param attributes named facts about it, lower case
 */
public record CapabilityProfile(Set<String> tokens, Map<String, Integer> numbers, Map<String, String> attributes) {

    public CapabilityProfile {
        tokens = Collections.unmodifiableSet(new LinkedHashSet<>(tokens));
        numbers = Collections.unmodifiableMap(new LinkedHashMap<>(numbers));
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** A board nothing is known about. */
    public static CapabilityProfile empty() {
        return new CapabilityProfile(Set.of(), Map.of(), Map.of());
    }

    /** A profile with its names made into slugs and its attribute values lower cased. */
    public static CapabilityProfile build(
            Collection<String> tokens, Map<String, ? extends Number> numbers, Map<String, ?> attributes) {
        Set<String> slugged = new LinkedHashSet<>();
        tokens.forEach(token -> slugged.add(Ids.slug(token)));
        Map<String, Integer> counts = new LinkedHashMap<>();
        numbers.forEach((key, value) -> counts.put(Ids.slug(key), value.intValue()));
        Map<String, String> named = new LinkedHashMap<>();
        attributes.forEach(
                (key, value) -> named.put(Ids.slug(key), String.valueOf(value).toLowerCase(Locale.ROOT)));
        return new CapabilityProfile(slugged, counts, named);
    }

    /** A profile of tokens alone. */
    public static CapabilityProfile build(Collection<String> tokens) {
        return build(tokens, Map.of(), Map.of());
    }

    /** The same profile with a card's capabilities folded in. */
    public CapabilityProfile withTokens(Collection<String> extra) {
        Set<String> merged = new LinkedHashSet<>(tokens);
        extra.forEach(token -> merged.add(Ids.slug(token)));
        return new CapabilityProfile(merged, numbers, attributes);
    }

    /** A count, defaulting to none of them rather than to missing. */
    public int number(String key) {
        return numbers.getOrDefault(key, 0);
    }

    /** A named fact about the board, if the bench knows it. */
    public Optional<String> attribute(String key) {
        return Optional.ofNullable(attributes.get(key));
    }
}
