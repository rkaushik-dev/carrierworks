package io.github.rkaushikdev.carrierworks.flash;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import io.github.rkaushikdev.carrierworks.util.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Erase counts for every board the bench has programmed, because flash wears out and boards get
 * reused for years.
 *
 * <p>A configuration flash is rated for a hundred thousand erase cycles per sector, which sounds
 * like a number nobody in a lab will ever reach. A board on the nightly regression gets programmed
 * twice a night, five nights a week, which is five hundred a year, and the boards in this lab are
 * eight years old. That is still nowhere near the rating. The reason to count anyway is that the
 * count is not spread evenly: every image starts at address zero, so sector zero has taken every
 * erase any image on that board has ever caused, while the sectors at the top of the part have taken
 * none.
 *
 * <p>So the ledger is per sector and per board, it is written from the plan rather than from an
 * intention, and it can say which sectors are getting close. A part that starts failing
 * verification after a program is usually a worn sector, and knowing that saves somebody from
 * replacing a perfectly good carrier.
 *
 * <p>Kept as one JSON file because it is small, it is written once per programming job, and an
 * operator being able to read it with {@code cat} has been worth more than any query it might have
 * supported. Boards are keyed by their normalised asset tag and sectors by their number, both in
 * order, two spaces to a level.
 */
public final class WearLedger {

    private static final int DEFAULT_HOTTEST = 5;
    private static final Comparator<SectorWear> MOST_WORN_FIRST =
            Comparator.comparingLong(SectorWear::erases).reversed().thenComparingLong(SectorWear::sector);

    private final Path path;
    private final Map<String, Map<Long, Long>> counts;

    /**
     * The ledger kept in this file, empty until the file exists.
     *
     * @throws IllegalArgumentException when the file is there but is not a ledger
     */
    public WearLedger(Path path) {
        this.path = path;
        this.counts = load(path);
    }

    /** Where the ledger is kept. */
    public Path path() {
        return path;
    }

    /** Write the ledger out, making its directory if need be. */
    public void save() {
        Map<String, Map<String, Long>> serialisable = new LinkedHashMap<>();
        counts.forEach((asset, sectors) -> {
            Map<String, Long> bySector = new LinkedHashMap<>();
            sectors.forEach((sector, count) -> bySector.put(Long.toString(sector), count));
            serialisable.put(asset, bySector);
        });
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, Json.writer().indent(2).write(serialisable), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * Count the erases a plan will cause, and return how many that was.
     *
     * <p>Called with the plan rather than after the run, because a plan that was half executed still
     * erased the sectors it got to, and undercounting wear is the failure that matters here.
     */
    public int record(String asset, FlashPlan plan) {
        Map<Long, Long> sectors = counts.computeIfAbsent(Records.boardKey(asset), ignored -> new TreeMap<>());
        int touched = 0;
        for (FlashStep step : plan.stepsOf(StepKind.ERASE)) {
            long first = plan.part().sectorOf(step.address());
            long last = plan.part().sectorOf(step.address() + step.length() - 1);
            for (long sector = first; sector <= last; sector++) {
                sectors.merge(sector, 1L, Long::sum);
                touched++;
            }
        }
        return touched;
    }

    /**
     * Load counts from somewhere else, replacing whatever is held for that board.
     *
     * <p>The lab kept this in a spreadsheet for years and the numbers in it are real history worth
     * carrying over rather than starting from zero.
     */
    public void restore(String asset, Map<Long, Long> history) {
        for (Map.Entry<Long, Long> entry : history.entrySet()) {
            if (entry.getKey() < 0 || entry.getValue() < 0) {
                throw new ValidationError("erase counts are not negative", "counts");
            }
        }
        counts.put(Records.boardKey(asset), new TreeMap<>(history));
    }

    /** How many times one sector of a board has been erased. */
    public long erases(String asset, long sector) {
        if (sector < 0) {
            throw new ValidationError("sectors are counted from zero", "sector", Details.of("given", sector));
        }
        return held(asset).getOrDefault(sector, 0L);
    }

    /** How many erases a board has taken across every sector. */
    public long total(String asset) {
        return held(asset).values().stream().mapToLong(Long::longValue).sum();
    }

    /** Every board with a count, in tag order. */
    public List<String> boards() {
        return List.copyOf(counts.keySet());
    }

    /** Every sector this board has erased, most worn first. */
    public List<SectorWear> wear(String asset, FlashSpec part) {
        return held(asset).entrySet().stream()
                .map(entry -> new SectorWear(entry.getKey(), entry.getValue(), part.eraseCycles()))
                .sorted(MOST_WORN_FIRST)
                .toList();
    }

    /** The five most worn sectors of a board. */
    public List<SectorWear> hottest(String asset, FlashSpec part) {
        return hottest(asset, part, DEFAULT_HOTTEST);
    }

    /** The most worn sectors of a board, at most {@code limit} of them. */
    public List<SectorWear> hottest(String asset, FlashSpec part, int limit) {
        if (limit < 1) {
            throw new ValidationError("ask for at least one sector", "limit", Details.of("given", limit));
        }
        return wear(asset, part).stream().limit(limit).toList();
    }

    /** The sectors of a board past four fifths of their rating, most worn first. */
    public List<SectorWear> wornSectors(String asset, FlashSpec part) {
        return wear(asset, part).stream().filter(SectorWear::worn).toList();
    }

    /** Whether any sector of a board is past four fifths of its rating. */
    public boolean isWorn(String asset, FlashSpec part) {
        return !wornSectors(asset, part).isEmpty();
    }

    private Map<Long, Long> held(String asset) {
        return counts.getOrDefault(Records.boardKey(asset), Map.of());
    }

    private static Map<String, Map<Long, Long>> load(Path path) {
        Map<String, Map<Long, Long>> counts = new TreeMap<>();
        if (!Files.isRegularFile(path)) {
            return counts;
        }
        Object raw;
        try {
            raw = Json.parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        if (!(raw instanceof Map<?, ?> boards)) {
            throw new IllegalArgumentException("a wear ledger maps boards to their sectors: " + path);
        }
        boards.forEach((asset, sectors) -> {
            if (!(sectors instanceof Map<?, ?> bySector)) {
                throw new IllegalArgumentException("a board in a wear ledger maps sectors to counts: " + asset);
            }
            Map<Long, Long> held = new TreeMap<>();
            bySector.forEach((sector, count) -> held.put(whole(sector), whole(count)));
            counts.put(String.valueOf(asset), held);
        });
        return counts;
    }

    /** A sector number or a count, written as a number or as its digits. */
    private static long whole(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            return Long.parseLong(text.strip());
        }
        throw new IllegalArgumentException("a wear ledger holds whole numbers, not " + value);
    }
}
