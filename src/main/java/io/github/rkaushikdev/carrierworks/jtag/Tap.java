package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Walks through the test access port controller, and the search that finds the shortest one.
 *
 * <p>Every JTAG operation is a walk through the sixteen state machine, so the whole of it is a
 * graph problem: get from where the port is to where the operation needs it, in as few clocks as
 * possible. Writing the paths out by hand is how a driver ends up with a sequence that works from
 * idle and hangs from pause, so they are searched instead.
 *
 * <p>Five ones is the one path that does not need searching. From any state at all, five clocks
 * with TMS high lands in test logic reset, which is why every session starts with them and why a
 * chain that will not talk gets them again.
 *
 * <p>TMS runs are lists of zeros and ones in the order they go out on the wire.
 */
public final class Tap {

    /** How many clocks with TMS high reach reset from anywhere. */
    public static final int RESET_CLOCKS = 5;

    private Tap() {}

    /**
     * Where a written run of TMS bits leaves the controller.
     *
     * <p>Bits are taken in the order they are written, which is the order they go out on the wire,
     * not the order an integer would number them. Spaces and underscores are grouping.
     */
    public static TapState walk(TapState state, String tmsBits) {
        String cleaned = tmsBits.strip().replace(" ", "").replace("_", "");
        if (!cleaned.chars().allMatch(ch -> ch == '0' || ch == '1')) {
            throw new ValidationError("tms bits are ones and zeros", "tms", Details.of("given", tmsBits));
        }
        TapState current = state;
        for (int index = 0; index < cleaned.length(); index++) {
            current = current.step(cleaned.charAt(index) == '1');
        }
        return current;
    }

    /** Where a run of TMS bits, first out first, leaves the controller. */
    public static TapState walk(TapState state, List<Integer> tmsBits) {
        for (Integer bit : tmsBits) {
            if (bit == null || (bit != 0 && bit != 1)) {
                throw new ValidationError("tms bits are ones and zeros", "tms");
            }
        }
        TapState current = state;
        for (int bit : tmsBits) {
            current = current.step(bit == 1);
        }
        return current;
    }

    /** The five clocks that land in reset from anywhere at all. */
    public static List<Integer> resetPath() {
        return Collections.nCopies(RESET_CLOCKS, 1);
    }

    /**
     * The shortest run of TMS bits taking the controller from one state to another; empty when it
     * is already there.
     *
     * <p>Breadth first, and ties broken towards TMS low, because a low first path keeps the
     * controller out of the select states where a stray clock lands it somewhere expensive.
     */
    public static List<Integer> pathBetween(TapState start, TapState end) {
        if (start == end) {
            return List.of();
        }
        Set<TapState> seen = EnumSet.of(start);
        Deque<Walk> queue = new ArrayDeque<>();
        queue.add(new Walk(start, List.of()));
        while (!queue.isEmpty()) {
            Walk walk = queue.removeFirst();
            for (int bit = 0; bit <= 1; bit++) {
                TapState next = walk.state().step(bit == 1);
                List<Integer> path = walk.then(bit);
                if (next == end) {
                    return path;
                }
                if (seen.add(next)) {
                    queue.addLast(new Walk(next, path));
                }
            }
        }
        throw new ValidationError(
                "no path between those states", "path", Details.of("start", start.value(), "end", end.value()));
    }

    /** A state reached and the bits that reached it. */
    private record Walk(TapState state, List<Integer> path) {

        List<Integer> then(int bit) {
            List<Integer> longer = new ArrayList<>(path);
            longer.add(bit);
            return List.copyOf(longer);
        }
    }
}
