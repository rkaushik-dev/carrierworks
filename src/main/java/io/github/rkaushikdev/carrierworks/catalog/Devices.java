package io.github.rkaushikdev.carrierworks.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** What is actually inside each part the lab keeps on the shelf. */
public final class Devices {

    /**
     * The revision nibble at the top of an idcode changes with the silicon stepping and tells us
     * nothing about which part is on the board.
     */
    public static final long IDCODE_MASK = 0x0FFF_FFFFL;

    private static final List<DeviceSpec> CATALOG = List.of(
            new DeviceSpec("xc7s25", "spartan7", 0x037C4093L, 6, 14600, 29200, 45, 80, 9_935_552L, 0),
            new DeviceSpec("xc7s50", "spartan7", 0x0362F093L, 6, 32600, 65200, 75, 120, 17_536_096L, 0),
            new DeviceSpec("xc7a35t", "artix7", 0x0362D093L, 6, 20800, 41600, 50, 90, 17_536_096L, 4),
            new DeviceSpec("xc7a50t", "artix7", 0x0362C093L, 6, 32600, 65200, 75, 120, 17_536_096L, 4),
            new DeviceSpec("xc7a100t", "artix7", 0x03631093L, 6, 63400, 126800, 135, 240, 30_606_304L, 4),
            new DeviceSpec("xc7a200t", "artix7", 0x03636093L, 6, 134600, 269200, 365, 740, 77_845_216L, 8),
            new DeviceSpec("xc7k70t", "kintex7", 0x03647093L, 6, 41000, 82000, 135, 240, 24_090_592L, 8),
            new DeviceSpec("xc7k325t", "kintex7", 0x03651093L, 6, 203800, 407600, 445, 840, 91_548_896L, 16),
            new DeviceSpec("xc7k410t", "kintex7", 0x03656093L, 6, 254200, 508400, 795, 1540, 127_023_328L, 16),
            new DeviceSpec("xc7z020", "zynq7000", 0x03727093L, 6, 53200, 106400, 140, 220, 32_364_512L, 0),
            new DeviceSpec(
                    "xcku040", "kintex_ultrascale", 0x03822093L, 6, 242400, 484800, 600, 1920, 126_411_296L, 20));

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
