package io.github.rkaushikdev.carrierworks.flash;

import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * What lives where in the configuration flash on a board: every region on one part, checked
 * against each other and the geometry.
 *
 * <p>A layout is a list of named regions with an address and a length, and the rules it has to
 * keep are all consequences of one fact: the smallest thing that can be erased is a sector. Two
 * regions inside one sector cannot be updated independently, so a layout that lets that happen is a
 * layout where updating the test image destroys the golden one. That is checked here and refused,
 * rather than discovered on a board in a customer's rack.
 *
 * <p>A region is allowed to start part way through a sector. That looks wrong at first and it is
 * deliberate: a small parameter block tucked above an image is normal, and the no shared sector
 * rule already guarantees that erasing whatever owns that sector cannot take anything else with it.
 *
 * <p>The golden image sits at zero because that is where the device looks after power on.
 * Everything else is somewhere the multiboot jump can reach, which is any sector boundary in the
 * part.
 *
 * @param part the flash part the layout is for
 * @param regions the regions, in the order they were given
 */
public record FlashLayout(FlashSpec part, List<FlashRegion> regions) {

    private static final Comparator<FlashRegion> BY_START = Comparator.comparingLong(FlashRegion::start);

    public FlashLayout {
        regions = List.copyOf(regions);
        if (regions.isEmpty()) {
            throw new ConfigurationError("a layout has regions in it");
        }
        List<String> names = regions.stream().map(FlashRegion::name).toList();
        if (new HashSet<>(names).size() != names.size()) {
            throw new ConfigurationError("two regions share a name", Details.of("names", names));
        }
        for (FlashRegion region : regions) {
            if (region.end() > part.capacityBytes()) {
                throw new ConfigurationError(
                        "a region runs off the end of the part",
                        Details.of("region", region.name(), "part", part.name()));
            }
        }
        List<FlashRegion> ordered = new ArrayList<>(regions);
        ordered.sort(BY_START);
        for (int index = 0; index + 1 < ordered.size(); index++) {
            FlashRegion first = ordered.get(index);
            FlashRegion second = ordered.get(index + 1);
            if (first.overlaps(second)) {
                throw new ConfigurationError(
                        "two regions overlap", Details.of("first", first.name(), "second", second.name()));
            }
            if (part.sectorOf(first.end() - 1) == part.sectorOf(second.start())) {
                throw new ConfigurationError(
                        "two regions share a sector, so one cannot be erased alone",
                        Details.of("first", first.name(), "second", second.name()));
            }
        }
    }

    /** One image at address zero, rounded up to whole sectors. */
    public static FlashLayout singleImage(FlashSpec part, long imageSize) {
        if (imageSize <= 0) {
            throw new ValidationError("an image has some bytes", "image_size");
        }
        long length = roundUpToSector(part, imageSize);
        if (length > part.capacityBytes()) {
            throw new ConfigurationError(
                    "that image does not fit the part",
                    Details.of("part", part.name(), "wanted", imageSize, "capacity", part.capacityBytes()));
        }
        return new FlashLayout(part, List.of(new FlashRegion("golden", 0, length, RegionPurpose.GOLDEN)));
    }

    /**
     * Golden in the bottom half of the part and update in the top half, which is how every board on
     * this bench is laid out.
     *
     * <p>On a thirty two megabyte part that puts the update image exactly at the sixteen megabyte
     * line, so whatever goes there has to be read with four address bytes.
     */
    public static FlashLayout halves(FlashSpec part) {
        long half = part.capacityBytes() / 2;
        return multiboot(part, half, half, half);
    }

    /**
     * A golden image at zero and an update image above it, with one spare sector between them.
     *
     * <p>The spare sector means growing the golden image slightly does not immediately need a new
     * update address.
     */
    public static FlashLayout multiboot(FlashSpec part, long goldenSize, long updateSize) {
        return multiboot(part, goldenSize, updateSize, Optional.empty());
    }

    /**
     * A golden image at zero and an update image at a pinned address.
     *
     * <p>The update address can be pinned because the warm boot address is baked into the golden
     * image, and a layout that moves it silently is a board that boots the wrong thing.
     */
    public static FlashLayout multiboot(FlashSpec part, long goldenSize, long updateSize, long updateStart) {
        return multiboot(part, goldenSize, updateSize, Optional.of(updateStart));
    }

    private static FlashLayout multiboot(FlashSpec part, long goldenSize, long updateSize, Optional<Long> pinned) {
        if (goldenSize <= 0 || updateSize <= 0) {
            throw new ValidationError("both images have some bytes", "size");
        }
        long goldenLength = roundUpToSector(part, goldenSize);
        long start = pinned.orElse(goldenLength + part.sectorBytes());
        if (!part.isSectorAligned(start)) {
            throw new ConfigurationError(
                    "the update image has to start on a sector boundary",
                    Details.of("start", start, "sector", part.sectorBytes()));
        }
        long updateLength = roundUpToSector(part, updateSize);
        if (start + updateLength > part.capacityBytes()) {
            throw new ConfigurationError(
                    "the pair does not fit the part",
                    Details.of("part", part.name(), "wanted", start + updateLength, "capacity", part.capacityBytes()));
        }
        return new FlashLayout(
                part,
                List.of(
                        new FlashRegion("golden", 0, goldenLength, RegionPurpose.GOLDEN),
                        new FlashRegion("update", start, updateLength, RegionPurpose.UPDATE)));
    }

    /**
     * The region with this name, however it was written.
     *
     * @throws NotFound when the layout has no such region
     */
    public FlashRegion region(String name) {
        String wanted = Ids.slug(name);
        return regions.stream()
                .filter(region -> region.name().equals(wanted))
                .findFirst()
                .orElseThrow(() -> new NotFound("flash region", wanted));
    }

    /** The region an address falls in, if any. */
    public Optional<FlashRegion> regionAt(long address) {
        return regions.stream().filter(region -> region.contains(address)).findFirst();
    }

    /** How many bytes the regions cover between them. */
    public long used() {
        return regions.stream().mapToLong(FlashRegion::length).sum();
    }

    /** How many bytes of the part no region covers. */
    public long free() {
        return part.capacityBytes() - used();
    }

    /** The first and last sector of a region, which is what an erase needs. */
    public SectorSpan sectorSpan(String name) {
        FlashRegion region = region(name);
        return new SectorSpan(part.sectorOf(region.start()), part.sectorOf(region.end() - 1));
    }

    /** How many sectors a region occupies. */
    public long sectorsFor(String name) {
        return sectorSpan(name).count();
    }

    /** {@code n25q128a13: golden 0x00000000 +4 MiB (golden), ...}, regions in address order. */
    public String describe() {
        return part.name() + ": "
                + regions.stream().sorted(BY_START).map(FlashRegion::describe).collect(Collectors.joining(", "));
    }

    private static long roundUpToSector(FlashSpec part, long size) {
        return Math.ceilDiv(size, part.sectorBytes()) * part.sectorBytes();
    }
}
