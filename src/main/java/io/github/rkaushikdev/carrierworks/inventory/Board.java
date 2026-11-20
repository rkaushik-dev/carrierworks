package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.AssetTag;
import io.github.rkaushikdev.carrierworks.BenchLocation;
import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.PduChannel;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.CardSpec;
import io.github.rkaushikdev.carrierworks.catalog.DeviceSpec;
import io.github.rkaushikdev.carrierworks.catalog.Devices;
import io.github.rkaushikdev.carrierworks.catalog.FlashParts;
import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import io.github.rkaushikdev.carrierworks.catalog.PartNumber;
import io.github.rkaushikdev.carrierworks.jtag.AdapterUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A carrier in a rack, and everything true about it.
 *
 * <p>A board is a carrier with a device on it, a flash beside the device, some connectors, a place
 * in a rack, an outlet feeding it and usually a cable hanging off it. Every one of those is a fact
 * somebody has to keep right, and the ones that interact are the ones that go wrong. Three
 * interactions are checked here and all three have bitten this lab.
 *
 * <p>A card asks the carrier for an adjustable rail voltage, and a carrier that is strapped to a
 * different one will bring the card up in a state where it enumerates and then behaves oddly under
 * load. Cards use serial transceivers, and the device has a fixed number of them: two four lane
 * cards on an Artix with four is a design nobody can route. And the cable has to be able to drive
 * the header. An adapter that only does 3.3 volts on a carrier whose header is at 1.8 will read a
 * chain of garbage, which looks exactly like a broken board.
 *
 * @param asset the asset tag
 * @param model the carrier model, as a slug
 * @param part the device's ordering code
 * @param flash the configuration flash part
 * @param location where it is racked
 * @param revision the carrier revision, as a slug
 * @param slots its connectors, in board order
 * @param adapter the cable hanging off it, if any
 * @param pdu the outlet feeding it, if known
 * @param vadjMillivolts what the adjustable rail is strapped to
 * @param headerVoltageMv the JTAG header's level
 * @param maxTckHz the fastest the header is rated for
 * @param notes anything else worth knowing
 */
public record Board(
        AssetTag asset,
        String model,
        PartNumber part,
        String flash,
        BenchLocation location,
        String revision,
        List<BoardSlot> slots,
        Optional<AdapterUnit> adapter,
        Optional<PduChannel> pdu,
        int vadjMillivolts,
        int headerVoltageMv,
        int maxTckHz,
        String notes) {

    /** The revision a carrier is unless somebody says otherwise. */
    public static final String DEFAULT_REVISION = "a";

    /** The adjustable rail and header level a carrier is strapped to unless somebody says otherwise. */
    public static final int DEFAULT_MILLIVOLTS = 1800;

    /** The JTAG clock ceiling a header has unless somebody says otherwise. */
    public static final int DEFAULT_MAX_TCK_HZ = 30_000_000;

    public Board {
        model = Ids.slug(model);
        revision = Ids.slug(revision);
        slots = List.copyOf(slots);
        DeviceSpec device = Devices.deviceFor(part);
        FlashParts.part(flash);
        List<String> names = slots.stream().map(BoardSlot::name).toList();
        if (new HashSet<>(names).size() != names.size()) {
            throw new ConfigurationError(
                    "two slots share a name", Details.of("board", asset.toString(), "slots", names));
        }
        List<CardSpec> fitted = fitted(slots);
        int used = fitted.stream().mapToInt(CardSpec::transceiversUsed).sum();
        if (used > device.transceivers()) {
            throw new ConfigurationError(
                    "the fitted cards want more transceivers than the device has",
                    Details.of("board", asset.toString(), "wanted", used, "have", device.transceivers()));
        }
        for (CardSpec spec : fitted) {
            if (spec.vadjMillivolts().isPresent() && spec.vadjMillivolts().get() != vadjMillivolts) {
                throw new ConfigurationError(
                        "a fitted card wants a different adjustable rail",
                        Details.of(
                                "board",
                                asset.toString(),
                                "fitted",
                                spec.name(),
                                "wants",
                                spec.vadjMillivolts().get(),
                                "strapped",
                                vadjMillivolts));
            }
        }
        if (adapter.isPresent() && !adapter.get().spec().drives(headerVoltageMv)) {
            throw new ConfigurationError(
                    "that cable cannot drive this board's header",
                    Details.of(
                            "board", asset.toString(), "adapter", adapter.get().model(), "header", headerVoltageMv));
        }
        if (maxTckHz <= 0) {
            throw new ValidationError("a board has a clock ceiling", "max_tck_hz");
        }
    }

    /**
     * A board described the way a config file describes one, where everything arrives as a string;
     * the rest is set on the builder.
     */
    public static Builder builder(String asset, String model, String part, String flash, String location) {
        return new Builder(AssetTag.parse(asset), model, PartNumber.parse(part), flash, BenchLocation.parse(location));
    }

    /** A builder holding everything this board holds, to change one or two things. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /** The asset tag as text, which is how everything else refers to the board. */
    public String tag() {
        return asset.toString();
    }

    /** The silicon on the board. */
    public DeviceSpec device() {
        return Devices.deviceFor(part);
    }

    /** The configuration flash on the board. */
    public FlashSpec flashSpec() {
        return FlashParts.part(flash);
    }

    /** One connector by name, or a not found. */
    public BoardSlot slot(String name) {
        String wanted = Ids.slug(name);
        return slots.stream()
                .filter(slot -> slot.name().equals(wanted))
                .findFirst()
                .orElseThrow(() -> new NotFound("slot", wanted));
    }

    /** The cards fitted, in slot order. */
    public List<CardSpec> fittedCards() {
        return fitted(slots);
    }

    /** The connectors with nothing in them, in slot order. */
    public List<BoardSlot> emptySlots() {
        return slots.stream().filter(BoardSlot::isEmpty).toList();
    }

    /** Transceivers the fitted cards leave free. */
    public int transceiversFree() {
        int used = fittedCards().stream().mapToInt(CardSpec::transceiversUsed).sum();
        return device().transceivers() - used;
    }

    /** What the fitted cards draw between them. */
    public int cardPowerMw() {
        return fittedCards().stream().mapToInt(CardSpec::powerDrawMw).sum();
    }

    /**
     * The same board with a card fitted in a slot, checked again.
     *
     * <p>Fitting a card is a physical act somebody has already done, so this cannot refuse to record
     * it. What it can do is refuse to record it wrongly, which is why the whole board is rebuilt
     * rather than patched.
     */
    public Board withCard(String slot, String card) {
        return refit(slot, Optional.of(card));
    }

    /** The same board with a slot emptied. */
    public Board withoutCard(String slot) {
        return refit(slot, Optional.empty());
    }

    private Board refit(String slot, Optional<String> card) {
        BoardSlot target = slot(slot);
        List<BoardSlot> refitted = new ArrayList<>();
        for (BoardSlot entry : slots) {
            refitted.add(entry.name().equals(target.name()) ? entry.withCard(card) : entry);
        }
        return toBuilder().slots(refitted).build();
    }

    /** {@code bx-14 rx-carrier-b rev a XC7K325T-2FFG900I at rack-2/shelf-04/slot-3 [fmc-adc-4ch-14b]}. */
    public String describe() {
        String fitted = fittedCards().stream().map(CardSpec::name).collect(Collectors.joining(", "));
        return tag() + " " + model + " rev " + revision + " " + part + " at " + location + " ["
                + (fitted.isEmpty() ? "bare" : fitted) + "]";
    }

    private static List<CardSpec> fitted(List<BoardSlot> slots) {
        return slots.stream().map(BoardSlot::cardSpec).flatMap(Optional::stream).toList();
    }

    /** A board assembled a fact at a time and checked once, on {@link #build()}. */
    public static final class Builder {

        private final AssetTag asset;
        private final String model;
        private final PartNumber part;
        private final String flash;
        private final BenchLocation location;
        private String revision = DEFAULT_REVISION;
        private List<BoardSlot> slots = List.of();
        private Optional<AdapterUnit> adapter = Optional.empty();
        private Optional<PduChannel> pdu = Optional.empty();
        private int vadjMillivolts = DEFAULT_MILLIVOLTS;
        private int headerVoltageMv = DEFAULT_MILLIVOLTS;
        private int maxTckHz = DEFAULT_MAX_TCK_HZ;
        private String notes = "";

        private Builder(AssetTag asset, String model, PartNumber part, String flash, BenchLocation location) {
            this.asset = asset;
            this.model = model;
            this.part = part;
            this.flash = flash;
            this.location = location;
        }

        private Builder(Board from) {
            this(from.asset, from.model, from.part, from.flash, from.location);
            revision = from.revision;
            slots = from.slots;
            adapter = from.adapter;
            pdu = from.pdu;
            vadjMillivolts = from.vadjMillivolts;
            headerVoltageMv = from.headerVoltageMv;
            maxTckHz = from.maxTckHz;
            notes = from.notes;
        }

        public Builder revision(String value) {
            revision = value;
            return this;
        }

        public Builder slots(List<BoardSlot> value) {
            slots = List.copyOf(value);
            return this;
        }

        public Builder slots(BoardSlot... value) {
            return slots(List.of(value));
        }

        /** The cable hanging off the board. */
        public Builder adapter(AdapterUnit value) {
            adapter = Optional.of(value);
            return this;
        }

        /** No cable: they get borrowed. */
        public Builder noAdapter() {
            adapter = Optional.empty();
            return this;
        }

        /** The outlet feeding the board. */
        public Builder pdu(PduChannel value) {
            pdu = Optional.of(value);
            return this;
        }

        /** No outlet on record. */
        public Builder noPdu() {
            pdu = Optional.empty();
            return this;
        }

        public Builder vadjMillivolts(int value) {
            vadjMillivolts = value;
            return this;
        }

        public Builder headerVoltageMv(int value) {
            headerVoltageMv = value;
            return this;
        }

        public Builder maxTckHz(int value) {
            maxTckHz = value;
            return this;
        }

        public Builder notes(String value) {
            notes = value;
            return this;
        }

        /** The board, checked. */
        public Board build() {
            return new Board(
                    asset,
                    model,
                    part,
                    flash,
                    location,
                    revision,
                    slots,
                    adapter,
                    pdu,
                    vadjMillivolts,
                    headerVoltageMv,
                    maxTckHz,
                    notes);
        }
    }
}
