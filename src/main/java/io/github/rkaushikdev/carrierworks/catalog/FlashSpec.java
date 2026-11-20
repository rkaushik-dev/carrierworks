package io.github.rkaushikdev.carrierworks.catalog;

import io.github.rkaushikdev.carrierworks.CatalogError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;

/**
 * One SPI configuration flash device, and its geometry.
 *
 * <p>Every number here is a constraint on something the bench does later. A page is the largest
 * write the part accepts in one go and writes that cross a page boundary wrap round to the start
 * of the same page instead of carrying on, which is a silent corruption rather than an error. A
 * subsector is the smallest thing that can be erased, so an image that starts halfway through one
 * cannot be updated without destroying whatever shares it. The address width decides whether the
 * part needs the four byte addressing command at all, and a part in three byte mode simply cannot
 * see anything past sixteen megabytes.
 *
 * <p>The erase and program times are the datasheet typicals. They are here so a job can say how
 * long it expects to take before it starts, because an operator watching a bar that has not moved
 * for four minutes will pull the cable.
 *
 * @param name the part, lower case
 * @param vendor who makes it
 * @param jedecId what the read id command answers
 * @param capacityBytes the whole part
 * @param pageBytes the largest single program
 * @param subsectorBytes the smallest erase
 * @param sectorBytes the usual erase
 * @param pageProgramUs typical page program time
 * @param subsectorEraseMs typical subsector erase time
 * @param sectorEraseMs typical sector erase time
 * @param bulkEraseS typical whole chip erase time
 * @param quadCapable whether it talks four bits wide
 * @param eraseCycles rated erase cycles per sector
 */
public record FlashSpec(
        String name,
        String vendor,
        int jedecId,
        long capacityBytes,
        int pageBytes,
        int subsectorBytes,
        int sectorBytes,
        int pageProgramUs,
        int subsectorEraseMs,
        int sectorEraseMs,
        int bulkEraseS,
        boolean quadCapable,
        int eraseCycles) {

    /** What a part is rated for when the datasheet is not consulted. */
    public static final int DEFAULT_ERASE_CYCLES = 100_000;

    public FlashSpec {
        if (capacityBytes % sectorBytes != 0) {
            throw new CatalogError("capacity is not a whole number of sectors", Details.of("part", name));
        }
        if (sectorBytes % subsectorBytes != 0) {
            throw new CatalogError("sector is not a whole number of subsectors", Details.of("part", name));
        }
        if (subsectorBytes % pageBytes != 0) {
            throw new CatalogError("subsector is not a whole number of pages", Details.of("part", name));
        }
    }

    /** A quad capable part rated for the usual hundred thousand erases. */
    public FlashSpec(
            String name,
            String vendor,
            int jedecId,
            long capacityBytes,
            int pageBytes,
            int subsectorBytes,
            int sectorBytes,
            int pageProgramUs,
            int subsectorEraseMs,
            int sectorEraseMs,
            int bulkEraseS) {
        this(
                name,
                vendor,
                jedecId,
                capacityBytes,
                pageBytes,
                subsectorBytes,
                sectorBytes,
                pageProgramUs,
                subsectorEraseMs,
                sectorEraseMs,
                bulkEraseS,
                true,
                DEFAULT_ERASE_CYCLES);
    }

    /** Three byte addressing tops out at sixteen megabytes. */
    public int addressBytes() {
        return capacityBytes <= 16L * 1024 * 1024 ? 3 : 4;
    }

    /** How many sectors the part has. */
    public long sectorCount() {
        return capacityBytes / sectorBytes;
    }

    /** How many subsectors make one sector. */
    public int subsectorsPerSector() {
        return sectorBytes / subsectorBytes;
    }

    /** The JEDEC manufacturer, the top byte of the id. */
    public int manufacturerId() {
        return (jedecId >> 16) & 0xFF;
    }

    /** Whether something of this size fits in the part. */
    public boolean holds(long size) {
        return 0 <= size && size <= capacityBytes;
    }

    /** Refuse an address outside the part. */
    public void checkAddress(long address) {
        if (address < 0 || address >= capacityBytes) {
            throw new ValidationError(
                    "address is outside the part", "address", Details.of("given", address, "part", name));
        }
    }

    /** The sector an address falls in. */
    public long sectorOf(long address) {
        checkAddress(address);
        return address / sectorBytes;
    }

    /** The first address of the sector an address falls in. */
    public long sectorBase(long address) {
        return sectorOf(address) * sectorBytes;
    }

    /** The subsector an address falls in. */
    public long subsectorOf(long address) {
        checkAddress(address);
        return address / subsectorBytes;
    }

    /** Whether an address starts a sector. */
    public boolean isSectorAligned(long address) {
        return Math.floorMod(address, sectorBytes) == 0;
    }

    /** Whether an address starts a subsector. */
    public boolean isSubsectorAligned(long address) {
        return Math.floorMod(address, subsectorBytes) == 0;
    }

    /**
     * How many page writes a run of bytes turns into.
     *
     * <p>The first page is usually partial, which is why this is not a division.
     */
    public long pagesSpanned(long address, long length) {
        if (length < 0) {
            throw new ValidationError("length must not be negative", "length", Details.of("given", length));
        }
        if (length == 0) {
            return 0;
        }
        checkAddress(address);
        long first = address / pageBytes;
        long last = (address + length - 1) / pageBytes;
        return last - first + 1;
    }
}
