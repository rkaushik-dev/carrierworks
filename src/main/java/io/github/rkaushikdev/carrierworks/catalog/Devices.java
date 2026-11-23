package io.github.rkaushikdev.carrierworks.catalog;

import io.github.rkaushikdev.carrierworks.util.JsonValues;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** What is actually inside each part the lab keeps on the shelf, as the parts file lists it. */
public final class Devices {

    /**
     * The revision nibble at the top of an idcode changes with the silicon stepping and tells us
     * nothing about which part is on the board.
     */
    public static final long IDCODE_MASK = 0x0FFF_FFFFL;

    private static final List<DeviceSpec> CATALOG = CatalogFile.parts("devices").stream()
            .map(entry -> new DeviceSpec(
                    JsonValues.text(entry.get("device")),
                    JsonValues.text(entry.get("family")),
                    CatalogFile.hex(entry, "idcode"),
                    CatalogFile.whole(entry, "ir_length", 0),
                    CatalogFile.whole(entry, "luts", 0),
                    CatalogFile.whole(entry, "flip_flops", 0),
                    CatalogFile.whole(entry, "block_ram_36k", 0),
                    CatalogFile.whole(entry, "dsp", 0),
                    JsonValues.whole(entry.get("config_bits")),
                    CatalogFile.whole(entry, "transceivers", 0)))
            .toList();

    /** Every device the lab stocks, by device code and by masked idcode. */
    public static final CatalogIndex<DeviceSpec> DEVICES = new CatalogIndex<>(
            "device", CATALOG, DeviceSpec::device, Map.of("idcode", spec -> spec.idcode() & IDCODE_MASK));

    private Devices() {}

    /** The device with this code, or nothing if the lab does not stock it. */
    public static Optional<DeviceSpec> lookup(String device) {
        return DEVICES.lookup(device);
    }

    /** The device behind a part number. */
    public static DeviceSpec deviceFor(PartNumber part) {
        return DEVICES.require(part.device());
    }

    /** The device behind a bare device code. */
    public static DeviceSpec deviceFor(String device) {
        return DEVICES.require(device);
    }

    /** The device that answered a scan, revision nibble ignored. */
    public static DeviceSpec forIdcode(long idcode) {
        return DEVICES.requireBy("idcode", idcode & IDCODE_MASK, String.format(Locale.ROOT, "0x%08x", idcode));
    }

    /** Every device in one family, in catalog order. */
    public static List<DeviceSpec> inFamily(String family) {
        String wanted = family.strip().toLowerCase(Locale.ROOT);
        return DEVICES.where(spec -> spec.family().equals(wanted));
    }

    /** Every device, in catalog order. */
    public static List<DeviceSpec> all() {
        return DEVICES.all();
    }
}
