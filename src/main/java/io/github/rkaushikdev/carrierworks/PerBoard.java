package io.github.rkaushikdev.carrierworks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Entries kept per board, oldest first, optionally capped.
 *
 * <p>Boards are keyed by their normalised asset tag, so {@code BX-0014} and {@code bx-14} are one
 * board. A board whose last entry goes is forgotten entirely, so it does not appear in the listing
 * with nothing beside it.
 *
 * @param <T> what is kept
 */
public final class PerBoard<T> {

    private final Integer keep;
    private final Map<String, List<T>> entries = new TreeMap<>();

    /** Every entry kept. */
    public PerBoard() {
        this.keep = null;
    }

    /** Only the newest {@code keep} entries kept for each board. */
    public PerBoard(int keep) {
        if (keep < 2) {
            throw new ValidationError("keep at least two entries", "keep", Details.of("given", keep));
        }
        this.keep = keep;
    }

    /** The cap, when there is one. */
    public OptionalInt keep() {
        return keep == null ? OptionalInt.empty() : OptionalInt.of(keep);
    }

    /** Add an entry for a board, dropping the oldest past the cap, and hand it back. */
    public T append(String board, T item) {
        List<T> held = entries.computeIfAbsent(Records.boardKey(board), ignored -> new ArrayList<>());
        held.add(item);
        if (keep != null && held.size() > keep) {
            held.subList(0, held.size() - keep).clear();
        }
        return item;
    }

    /** One board's entries, oldest first. */
    public List<T> items(String board) {
        return List.copyOf(entries.getOrDefault(Records.boardKey(board), List.of()));
    }

    /** One board's newest entry, if it has any. */
    public Optional<T> latest(String board) {
        List<T> held = entries.get(Records.boardKey(board));
        return held == null || held.isEmpty() ? Optional.empty() : Optional.of(held.getLast());
    }

    /** Every board with an entry, in tag order. */
    public List<String> boards() {
        return List.copyOf(entries.keySet());
    }

    /** Every entry, board by board in tag order. */
    public List<T> all() {
        List<T> found = new ArrayList<>();
        entries.values().forEach(found::addAll);
        return List.copyOf(found);
    }

    /** Replace a board's entries outright; nothing at all forgets the board. */
    public void replace(String board, Iterable<? extends T> items) {
        List<T> kept = new ArrayList<>();
        items.forEach(kept::add);
        if (kept.isEmpty()) {
            entries.remove(Records.boardKey(board));
        } else {
            entries.put(Records.boardKey(board), kept);
        }
    }

    /** Forget a board, returning how many entries went with it. */
    public int drop(String board) {
        List<T> held = entries.remove(Records.boardKey(board));
        return held == null ? 0 : held.size();
    }

    /** Drop everything the predicate does not want, and say how many. */
    public int prune(Predicate<? super T> wanted) {
        int dropped = 0;
        var boards = entries.entrySet().iterator();
        while (boards.hasNext()) {
            List<T> held = boards.next().getValue();
            int before = held.size();
            held.removeIf(wanted.negate());
            dropped += before - held.size();
            if (held.isEmpty()) {
                boards.remove();
            }
        }
        return dropped;
    }

    /** Whether a board has any entries; anything that is not a tag never does. */
    public boolean contains(Object board) {
        return board instanceof String tag && entries.containsKey(Records.boardKey(tag));
    }

    /** How many entries there are across every board. */
    public int size() {
        return entries.values().stream().mapToInt(List::size).sum();
    }
}
