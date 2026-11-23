package io.github.rkaushikdev.carrierworks.flash;

import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import java.util.ArrayList;
import java.util.List;

/**
 * The size of block a plan erases in.
 *
 * <p>Subsector erases are offered because rewriting a small parameter block with four kilobyte
 * erases is much faster than with sixty four kilobyte ones. For a whole image they are slower,
 * since a sector is sixteen subsectors and the per command overhead adds up, so the caller chooses.
 *
 * <p>Or lets the plan choose. A fitted erase takes each sector the write touches on its own and
 * uses whichever is quicker by the datasheet: one sector erase, or the subsectors the write
 * actually reaches. An image that ends a few kilobytes into its last sector, which is nearly every
 * image, then pays for those few subsectors instead of a whole sector at the tail.
 */
public enum EraseUnit {
    /** The part's usual erase, sixty four kilobytes on every part the lab stocks. */
    SECTOR,
    /** The part's smallest erase, four kilobytes on every part the lab stocks. */
    SUBSECTOR,
    /** Whole sectors where that is quicker, subsectors where it is not. */
    FITTED;

    /**
     * The erases that clear everything from the block {@code start} falls in up to {@code end}, in
     * address order, each with its typical time.
     */
    public List<FlashStep> cover(FlashSpec part, long start, long end) {
        return switch (this) {
            case SECTOR -> blocks(start, end, part.sectorBytes(), part.sectorEraseMs());
            case SUBSECTOR -> blocks(start, end, part.subsectorBytes(), part.subsectorEraseMs());
            case FITTED -> fitted(part, start, end);
        };
    }

    private static List<FlashStep> blocks(long start, long end, long size, long cost) {
        List<FlashStep> steps = new ArrayList<>();
        for (long address = Math.floorDiv(start, size) * size; address < end; address += size) {
            steps.add(new FlashStep(StepKind.ERASE, address, size, cost));
        }
        return steps;
    }

    private static List<FlashStep> fitted(FlashSpec part, long start, long end) {
        long sector = part.sectorBytes();
        List<FlashStep> steps = new ArrayList<>();
        for (long base = Math.floorDiv(start, sector) * sector; base < end; base += sector) {
            List<FlashStep> small = blocks(
                    Math.max(start, base),
                    Math.min(end, base + sector),
                    part.subsectorBytes(),
                    part.subsectorEraseMs());
            long smallCost = small.stream().mapToLong(FlashStep::estimatedMs).sum();
            if (smallCost < part.sectorEraseMs()) {
                steps.addAll(small);
            } else {
                steps.add(new FlashStep(StepKind.ERASE, base, sector, part.sectorEraseMs()));
            }
        }
        return steps;
    }
}
