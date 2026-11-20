package io.github.rkaushikdev.carrierworks.flash;

import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;

/**
 * The size of block a plan erases in.
 *
 * <p>Subsector erases are offered because rewriting a small parameter block with four kilobyte
 * erases is much faster than with sixty four kilobyte ones. For a whole image they are slower,
 * since a sector is sixteen subsectors and the per command overhead adds up, so the caller chooses.
 */
public enum EraseUnit {
    /** The part's usual erase, sixty four kilobytes on every part the lab stocks. */
    SECTOR,
    /** The part's smallest erase, four kilobytes on every part the lab stocks. */
    SUBSECTOR;

    /** How many bytes one erase of this size clears on a part. */
    public long bytes(FlashSpec part) {
        return this == SECTOR ? part.sectorBytes() : part.subsectorBytes();
    }

    /** What one erase of this size typically takes on a part, in milliseconds. */
    public long milliseconds(FlashSpec part) {
        return this == SECTOR ? part.sectorEraseMs() : part.subsectorEraseMs();
    }
}
