package io.github.rkaushikdev.carrierworks.catalog;

import io.github.rkaushikdev.carrierworks.util.JsonValues;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The configuration flash parts the boards carry, as the parts file lists them. */
public final class FlashParts {

    private static final long MIB = 1024L * 1024;

    private static final List<FlashSpec> CATALOG = CatalogFile.parts("flash").stream()
            .map(entry -> new FlashSpec(
                    JsonValues.text(entry.get("name")),
                    JsonValues.text(entry.get("vendor")),
                    Math.toIntExact(CatalogFile.hex(entry, "jedec_id")),
                    CatalogFile.whole(entry, "capacity_mib", 0) * MIB,
                    CatalogFile.whole(entry, "page_bytes", 0),
                    CatalogFile.whole(entry, "subsector_bytes", 0),
                    CatalogFile.whole(entry, "sector_bytes", 0),
                    CatalogFile.whole(entry, "page_program_us", 0),
                    CatalogFile.whole(entry, "subsector_erase_ms", 0),
                    CatalogFile.whole(entry, "sector_erase_ms", 0),
                    CatalogFile.whole(entry, "bulk_erase_s", 0),
                    CatalogFile.flag(entry, "quad", true),
                    CatalogFile.whole(entry, "erase_cycles", FlashSpec.DEFAULT_ERASE_CYCLES)))
            .toList();

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
