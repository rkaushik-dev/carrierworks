package io.github.rkaushikdev.carrierworks.flash;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.Measure;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Locale;
import java.util.Objects;

/**
 * One named stretch of a configuration flash part.
 *
 * @param name the region's name, as a slug
 * @param start the first byte address
 * @param length how many bytes it covers
 * @param purpose what it is kept for
 */
public record FlashRegion(String name, long start, long length, RegionPurpose purpose) {

    public FlashRegion {
        name = Ids.slug(name);
        if (start < 0) {
            throw new ValidationError("a region starts inside the part", "start");
        }
        if (length <= 0) {
            throw new ValidationError("a region has a length", "length", Details.of("given", length));
        }
        Objects.requireNonNull(purpose, "purpose");
    }

    /** A region that holds data rather than an image. */
    public FlashRegion(String name, long start, long length) {
        this(name, start, length, RegionPurpose.DATA);
    }

    /** One past the last byte. */
    public long end() {
        return start + length;
    }

    /** Whether an address falls inside the region. */
    public boolean contains(long address) {
        return start <= address && address < end();
    }

    /** Whether two regions have any byte in common. */
    public boolean overlaps(FlashRegion other) {
        return start < other.end() && other.start < end();
    }

    /** Whether something of this size fits in the region. */
    public boolean holds(long size) {
        return 0 <= size && size <= length;
    }

    /** {@code update 0x00400000 +4 MiB (update)}. */
    public String describe() {
        return String.format(Locale.ROOT, "%s 0x%08x +%s (%s)", name, start, Measure.formatSize(length), purpose);
    }
}
