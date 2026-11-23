package io.github.rkaushikdev.carrierworks.catalog;

import io.github.rkaushikdev.carrierworks.CatalogError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.util.Json;
import io.github.rkaushikdev.carrierworks.util.JsonValues;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * One of the catalogue's data files, read off the class path.
 *
 * <p>The lab's stock changes far more often than the code that reasons about it: a card bought, a
 * carrier revision with one more rail, a controller added to a header. Those facts live in JSON
 * next to the classes, and this is the one place that reads them and refuses one that does not
 * hold together, at start up rather than on the day the missing entry is asked for.
 */
public final class CatalogFile {

    /** The parts the lab stocks: devices, configuration flash and programming cables. */
    public static final String PARTS = "/catalog/parts.json";

    /** The carrier models: what each puts on the JTAG header and which rails it brings up. */
    public static final String CARRIERS = "/catalog/carriers.json";

    private CatalogFile() {}

    /**
     * The object a data file holds.
     *
     * @throws CatalogError when the file is missing or is not a JSON object
     */
    public static Map<?, ?> load(String resource) {
        try (InputStream in = CatalogFile.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new CatalogError("a catalogue file is missing", Details.of("resource", resource));
            }
            if (!(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)) instanceof Map<?, ?> document)) {
                throw new CatalogError("a catalogue file is not an object", Details.of("resource", resource));
            }
            return document;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** The entries of a list member, each of which has to be an object; none when the member is absent. */
    public static List<Map<?, ?>> objects(Map<?, ?> owner, String member) {
        Object value = JsonValues.get(owner, member, List.of());
        if (!(value instanceof List<?> items)) {
            throw new CatalogError("a list was expected in the catalogue", Details.of("member", member));
        }
        return items.stream()
                .<Map<?, ?>>map(item -> {
                    if (!(item instanceof Map<?, ?> entry)) {
                        throw new CatalogError(
                                "an entry in the catalogue is not an object",
                                Details.of("given", JsonValues.text(item)));
                    }
                    return entry;
                })
                .toList();
    }

    /** The texts of a list member; none when the member is absent. */
    public static List<String> texts(Map<?, ?> owner, String member) {
        Object value = JsonValues.get(owner, member, List.of());
        if (!(value instanceof List<?> items)) {
            throw new CatalogError("a list was expected in the catalogue", Details.of("member", member));
        }
        return items.stream().map(JsonValues::text).toList();
    }

    /** The whole numbers of a list member; none when the member is absent. */
    public static List<Integer> wholes(Map<?, ?> owner, String member) {
        Object value = JsonValues.get(owner, member, List.of());
        if (!(value instanceof List<?> items)) {
            throw new CatalogError("a list was expected in the catalogue", Details.of("member", member));
        }
        return items.stream()
                .map(item -> Math.toIntExact(JsonValues.whole(item)))
                .toList();
    }

    /** A whole number member, or the fallback when it is absent. */
    public static int whole(Map<?, ?> owner, String member, int fallback) {
        return Math.toIntExact(JsonValues.whole(JsonValues.get(owner, member, (long) fallback)));
    }

    /** A flag member, or the fallback when it is absent. */
    public static boolean flag(Map<?, ?> owner, String member, boolean fallback) {
        Object value = JsonValues.get(owner, member, fallback);
        if (!(value instanceof Boolean set)) {
            throw new CatalogError("a flag was expected in the catalogue", Details.of("member", member));
        }
        return set;
    }

    /** A hex member, such as an idcode or a JEDEC id, read as a number. */
    public static long hex(Map<?, ?> owner, String member) {
        return IdCode.parse(JsonValues.text(owner.get(member))).value();
    }

    /** One section of the parts file. */
    public static List<Map<?, ?>> parts(String section) {
        return objects(load(PARTS), section);
    }

    /** Every carrier model in the carrier file, by model. */
    public static Map<?, ?> carriers() {
        return load(CARRIERS);
    }
}
