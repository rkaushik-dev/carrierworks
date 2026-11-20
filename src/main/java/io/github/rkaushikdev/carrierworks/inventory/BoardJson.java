package io.github.rkaushikdev.carrierworks.inventory;

import static io.github.rkaushikdev.carrierworks.util.JsonValues.get;
import static io.github.rkaushikdev.carrierworks.util.JsonValues.text;
import static io.github.rkaushikdev.carrierworks.util.JsonValues.truthy;
import static io.github.rkaushikdev.carrierworks.util.JsonValues.whole;

import io.github.rkaushikdev.carrierworks.BenchError;
import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.PduChannel;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.jtag.AdapterUnit;
import io.github.rkaushikdev.carrierworks.util.JsonValues;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One board to and from the JSON object the bench file uses.
 *
 * <p>Kept apart from {@link BenchFile} because that is about the document and this is about a
 * board, and because the http layer serialises a board with exactly these fields. Two writers of
 * the same shape is how they drift.
 *
 * <p>Everything optional is omitted rather than written as {@code null}. A bench file that somebody
 * edits by hand should not be full of nulls for the cables that are not plugged in today. Reading
 * is forgiving in the way {@link JsonValues} describes: a required field, the cable or the outlet
 * holding {@code null}, an empty string or zero counts as left out, and numbers may arrive as text.
 */
public final class BoardJson {

    private static final List<String> REQUIRED = List.of("asset", "model", "part", "flash", "location");

    private BoardJson() {}

    /**
     * One board out of its JSON object.
     *
     * <p>A missing field, and a board whose parts do not go together, are configuration errors. Any
     * other failure comes out as it was raised, and the bench file wraps it with the board it was
     * reading.
     *
     * @throws ConfigurationError naming the board and every missing field when a required one is
     *     left out, or when the board's own checks refuse it
     * @throws BenchError when a value does not read as what it names, or a slot or the cable is not
     *     an object with the members it needs
     * @throws IllegalArgumentException when a number does not read as a whole one
     * @throws ArithmeticException when a whole number is too large for its field
     */
    public static Board fromJson(Map<?, ?> entry) {
        List<String> missing =
                REQUIRED.stream().filter(field -> !truthy(entry.get(field))).toList();
        if (!missing.isEmpty()) {
            throw new ConfigurationError(
                    "a board entry is missing fields",
                    Details.of("board", text(get(entry, "asset", "unnamed")), "missing", String.join(", ", missing)));
        }
        List<BoardSlot> slots = new ArrayList<>();
        for (Object item : list(get(entry, "slots", List.of()))) {
            Map<?, ?> slot = object(item, "a slot", "slots");
            Object fitted = slot.get("fitted");
            slots.add(new BoardSlot(
                    text(required(slot, "name", "a slot")),
                    text(required(slot, "connector", "a slot")),
                    fitted == null || "".equals(fitted) ? Optional.empty() : Optional.of(text(fitted))));
        }
        Optional<AdapterUnit> adapter = Optional.empty();
        if (truthy(entry.get("adapter"))) {
            Map<?, ?> raw = object(entry.get("adapter"), "the adapter", "adapter");
            adapter = Optional.of(new AdapterUnit(
                    text(required(raw, "serial", "the adapter")),
                    text(required(raw, "model", "the adapter")),
                    text(get(raw, "port", ""))));
        }
        Optional<PduChannel> pdu =
                truthy(entry.get("pdu")) ? Optional.of(PduChannel.parse(text(entry.get("pdu")))) : Optional.empty();
        Board.Builder builder = Board.builder(
                        text(entry.get("asset")),
                        text(entry.get("model")),
                        text(entry.get("part")),
                        text(entry.get("flash")),
                        text(entry.get("location")))
                .revision(text(get(entry, "revision", Board.DEFAULT_REVISION)))
                .slots(slots)
                .vadjMillivolts(integer(entry, "vadj_millivolts", Board.DEFAULT_MILLIVOLTS))
                .headerVoltageMv(integer(entry, "header_voltage_mv", Board.DEFAULT_MILLIVOLTS))
                .maxTckHz(integer(entry, "max_tck_hz", Board.DEFAULT_MAX_TCK_HZ))
                .notes(text(get(entry, "notes", "")));
        adapter.ifPresent(builder::adapter);
        pdu.ifPresent(builder::pdu);
        return builder.build();
    }

    /**
     * The JSON object for a board, with nothing empty written out.
     *
     * <p>Members come in a fixed order: identity, placement and levels first, then the slots, the
     * cable, the outlet and the notes when there are any. The map is read only.
     */
    public static Map<String, Object> toJson(Board board) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("asset", board.tag());
        entry.put("model", board.model());
        entry.put("revision", board.revision());
        entry.put("part", board.part().orderingCode());
        entry.put("flash", board.flash());
        entry.put("location", board.location().toString());
        entry.put("vadj_millivolts", board.vadjMillivolts());
        entry.put("header_voltage_mv", board.headerVoltageMv());
        entry.put("max_tck_hz", board.maxTckHz());
        if (!board.slots().isEmpty()) {
            entry.put("slots", board.slots().stream().map(BoardJson::slot).toList());
        }
        board.adapter().ifPresent(cable -> {
            Map<String, Object> adapter = new LinkedHashMap<>();
            adapter.put("serial", cable.serial());
            adapter.put("model", cable.model());
            if (!cable.port().isEmpty()) {
                adapter.put("port", cable.port());
            }
            entry.put("adapter", Collections.unmodifiableMap(adapter));
        });
        board.pdu().ifPresent(channel -> entry.put("pdu", channel.toString()));
        if (!board.notes().isEmpty()) {
            entry.put("notes", board.notes());
        }
        return Collections.unmodifiableMap(entry);
    }

    private static Map<String, Object> slot(BoardSlot slot) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", slot.name());
        entry.put("connector", slot.connector());
        slot.fitted().ifPresent(card -> entry.put("fitted", card));
        return Collections.unmodifiableMap(entry);
    }

    private static int integer(Map<?, ?> entry, String key, int fallback) {
        return Math.toIntExact(whole(get(entry, key, fallback)));
    }

    private static List<?> list(Object value) {
        if (value instanceof List<?> items) {
            return items;
        }
        throw new ValidationError("the slots are not a list", "slots");
    }

    private static Map<?, ?> object(Object value, String what, String field) {
        if (value instanceof Map<?, ?> members) {
            return members;
        }
        throw new ValidationError(what + " is not an object", field);
    }

    private static Object required(Map<?, ?> object, String key, String what) {
        if (!object.containsKey(key)) {
            throw new ValidationError(what + " has no " + key, key);
        }
        return object.get(key);
    }
}
