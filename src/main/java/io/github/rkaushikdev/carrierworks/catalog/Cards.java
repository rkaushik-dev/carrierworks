package io.github.rkaushikdev.carrierworks.catalog;

import io.github.rkaushikdev.carrierworks.CatalogError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.util.Json;
import io.github.rkaushikdev.carrierworks.util.JsonValues;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Daughtercards, the connectors they land on and what they bring with them.
 *
 * <p>A board on its own is a device and some LEDs. What makes it the right board for a particular
 * regression is whatever is bolted to the front of it, which is why the scheduler matches on the
 * capabilities a card provides rather than on the card by name. Nobody asks for a specific
 * converter module; they ask for two channels of sixteen bit capture and do not care whose.
 *
 * <p>The connector rule is the one that catches people. A low pin count FMC card drops into a high
 * pin count carrier and works, because HPC is LPC plus more signals. The reverse does not, and the
 * card looks seated because the mechanical part of the connector is identical. That single
 * asymmetry is why this has a fit rule rather than an equality check. An FMC+ carrier extends the
 * same rule one step further: it takes FMC+ cards with their extra transceiver bank, and HPC and
 * LPC cards in the same socket.
 *
 * <p>The catalogue is data, in {@value #RESOURCE}, because it changes every time somebody buys a
 * card and nobody should have to read Java to stock one.
 */
public final class Cards {

    /** Where the catalogue is kept, on the class path. */
    public static final String RESOURCE = "/catalog/cards.json";

    private static final Map<?, ?> DOCUMENT = load();

    /** Every connector kind the lab has, and what each will physically accept. */
    public static final Map<String, Set<String>> CONNECTORS = connectors(DOCUMENT);

    private static final List<CardSpec> CATALOG = cards(DOCUMENT);

    /** Every card the lab stocks, by name. */
    public static final CatalogIndex<CardSpec> CARDS = new CatalogIndex<>("card", CATALOG, CardSpec::name);

    private Cards() {}

    /** Whether a card with this edge fits a carrier with that socket. */
    public static boolean connectorAccepts(String carrier, String cardConnector) {
        Set<String> accepted = CONNECTORS.get(carrier);
        if (accepted == null) {
            throw new NotFound("connector", carrier);
        }
        return accepted.contains(cardConnector);
    }

    /** The card with this name, or nothing. */
    public static Optional<CardSpec> lookup(String name) {
        return CARDS.lookup(name);
    }

    /** The card with this name, or a not found. */
    public static CardSpec card(String name) {
        return CARDS.require(name);
    }

    /** Every card that brings a given capability, cheapest on power first. */
    public static List<CardSpec> providing(String capability) {
        String wanted = Ids.slug(capability);
        return CARDS.where(spec -> spec.provides().contains(wanted)).stream()
                .sorted(Comparator.comparingInt(CardSpec::powerDrawMw).thenComparing(CardSpec::name))
                .toList();
    }

    /** Every card, in catalog order. */
    public static List<CardSpec> all() {
        return CARDS.all();
    }

    private static Map<?, ?> load() {
        try (InputStream in = Cards.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new CatalogError("the card catalogue is missing", Details.of("resource", RESOURCE));
            }
            if (!(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)) instanceof Map<?, ?> document)) {
                throw new CatalogError("the card catalogue is not an object", Details.of("resource", RESOURCE));
            }
            return document;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Map<String, Set<String>> connectors(Map<?, ?> document) {
        Map<String, Set<String>> found = new LinkedHashMap<>();
        if (document.get("connectors") instanceof Map<?, ?> table) {
            table.forEach((carrier, accepts) -> found.put(String.valueOf(carrier), Set.copyOf(texts(accepts))));
        }
        return Collections.unmodifiableMap(found);
    }

    /**
     * One card per entry. A card with no {@code vadj_mv} leaves the adjustable rail alone, and one
     * with no {@code transceivers} uses none.
     */
    static List<CardSpec> cards(Map<?, ?> document) {
        List<CardSpec> found = new ArrayList<>();
        if (!(document.get("cards") instanceof List<?> entries)) {
            throw new CatalogError("the card catalogue lists no cards", Details.of("resource", RESOURCE));
        }
        for (Object item : entries) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new CatalogError(
                        "a card in the catalogue is not an object", Details.of("given", JsonValues.text(item)));
            }
            Object vadj = entry.get("vadj_mv");
            found.add(new CardSpec(
                    JsonValues.text(entry.get("name")),
                    JsonValues.text(entry.get("vendor")),
                    JsonValues.text(entry.get("connector")),
                    Set.copyOf(texts(entry.get("provides"))),
                    (int) JsonValues.whole(entry.get("power_draw_mw")),
                    vadj == null ? Optional.empty() : Optional.of((int) JsonValues.whole(vadj)),
                    (int) JsonValues.whole(JsonValues.get(entry, "transceivers", 0L)),
                    JsonValues.text(JsonValues.get(entry, "notes", "")),
                    Set.copyOf(texts(JsonValues.get(entry, "tags", List.of())))));
        }
        return List.copyOf(found);
    }

    private static List<String> texts(Object value) {
        if (!(value instanceof List<?> items)) {
            throw new CatalogError(
                    "a list was expected in the card catalogue", Details.of("given", JsonValues.text(value)));
        }
        return items.stream().map(JsonValues::text).toList();
    }
}
