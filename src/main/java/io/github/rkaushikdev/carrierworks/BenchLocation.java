package io.github.rkaushikdev.carrierworks;

import io.github.rkaushikdev.carrierworks.util.Repr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A slot in a shelf in a rack.
 *
 * <p>The bench is racks of twelve shelves, six slots to a shelf, and the slots are numbered left to
 * right as you face the rack. Shelves are numbered from the floor up, which matters for airflow:
 * see {@link Locations#downwindOf}.
 *
 * @param rack the rack, as a slug
 * @param shelf one to {@value Locations#SHELVES_PER_RACK}
 * @param slot one to {@value Locations#SLOTS_PER_SHELF}
 */
public record BenchLocation(String rack, int shelf, int slot) implements Comparable<BenchLocation> {

    private static final Pattern LOCATION =
            Pattern.compile("(?<rack>[a-z0-9][a-z0-9-]*)[/:](?:shelf-?)?(?<shelf>\\d{1,2})[/:](?:slot-?)?(?<slot>\\d)");

    private static final Comparator<BenchLocation> ORDER = Comparator.comparing(BenchLocation::rack)
            .thenComparingInt(BenchLocation::shelf)
            .thenComparingInt(BenchLocation::slot);

    public BenchLocation {
        rack = Ids.slug(rack);
        if (shelf < 1 || shelf > Locations.SHELVES_PER_RACK) {
            throw new ValidationError("shelf out of range", "shelf", Details.of("given", shelf));
        }
        if (slot < 1 || slot > Locations.SLOTS_PER_SHELF) {
            throw new ValidationError("slot out of range", "slot", Details.of("given", slot));
        }
    }

    /** Read a location however it was typed: {@code rack-2/shelf-04/slot-3}, {@code rack-2:4:3}. */
    public static BenchLocation parse(String text) {
        String cleaned = text.strip().toLowerCase(Locale.ROOT).replace(" ", "");
        Matcher match = LOCATION.matcher(cleaned);
        if (!match.matches()) {
            throw new ValidationError("cannot read a location from " + Repr.of(text), "location");
        }
        return new BenchLocation(
                match.group("rack"), Integer.parseInt(match.group("shelf")), Integer.parseInt(match.group("slot")));
    }

    /** Whether two locations are on the same shelf of the same rack, whatever the slot. */
    public boolean sameShelf(BenchLocation other) {
        return rack.equals(other.rack) && shelf == other.shelf;
    }

    /** Higher shelf number is higher off the floor. */
    public boolean isAbove(BenchLocation other) {
        return rack.equals(other.rack) && shelf > other.shelf;
    }

    /** The shelves this one sits under, nearest first, stopping at the top of the rack. */
    public List<BenchLocation> shelvesAbove(int count) {
        if (count < 0) {
            throw new ValidationError("count must not be negative", "count", Details.of("given", count));
        }
        int top = Math.min(shelf + count, Locations.SHELVES_PER_RACK);
        List<BenchLocation> above = new ArrayList<>();
        for (int next = shelf + 1; next <= top; next++) {
            above.add(new BenchLocation(rack, next, slot));
        }
        return List.copyOf(above);
    }

    @Override
    public int compareTo(BenchLocation other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s/shelf-%02d/slot-%d", rack, shelf, slot);
    }
}
