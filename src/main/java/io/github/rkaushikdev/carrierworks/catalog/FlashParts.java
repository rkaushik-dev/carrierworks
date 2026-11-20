package io.github.rkaushikdev.carrierworks.catalog;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The configuration flash parts the boards carry. */
public final class FlashParts {

    private static final long MIB = 1024L * 1024;

    private static final List<FlashSpec> CATALOG = List.of(
            new FlashSpec("n25q128a13", "micron", 0x20BA18, 16 * MIB, 256, 4096, 65536, 500, 300, 700, 250),
            new FlashSpec("n25q256a11", "micron", 0x20BB19, 32 * MIB, 256, 4096, 65536, 500, 300, 700, 480),
            new FlashSpec("mt25ql512", "micron", 0x20BA20, 64 * MIB, 256, 4096, 65536, 400, 400, 1000, 600),
            new FlashSpec("mx25l25635f", "macronix", 0xC22019, 32 * MIB, 256, 4096, 65536, 750, 250, 900, 300),
            new FlashSpec("s25fl128s", "spansion", 0x012018, 16 * MIB, 256, 4096, 65536, 600, 290, 520, 165),
            new FlashSpec(
                    "w25q64jv", "winbond", 0xEF4017, 8 * MIB, 256, 4096, 65536, 700, 120, 1000, 100, true, 100_000));

    /** Every part the lab stocks, by name and by JEDEC id. */
    public static final CatalogIndex<FlashSpec> FLASH_PARTS =
            new CatalogIndex<>("flash part", CATALOG, FlashSpec::name, Map.of("jedec id", FlashSpec::jedecId));

    private FlashParts() {}

    /** The part with this name, or nothing. */
    public static Optional<FlashSpec> lookup(String name) {
        return FLASH_PARTS.lookup(name);
    }

    /** The part with this name, or a not found. */
    public static FlashSpec part(String name) {
        return FLASH_PARTS.require(name);
    }

    /** Whatever answered the read id command. */
    public static FlashSpec forJedecId(int jedecId) {
        return FLASH_PARTS.requireBy("jedec id", jedecId, String.format(Locale.ROOT, "0x%06x", jedecId));
    }

    /** Every part, in catalog order. */
    public static List<FlashSpec> all() {
        return FLASH_PARTS.all();
    }
}
