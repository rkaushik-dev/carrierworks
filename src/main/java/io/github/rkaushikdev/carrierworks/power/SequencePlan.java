package io.github.rkaushikdev.carrierworks.power;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Everything to do to bring a board up or down, in order, and what would stop it.
 *
 * <p>A sequence is built and then run, the same way a flash plan is, and for the same reason:
 * somebody is going to want to see what is about to happen to a board before it happens.
 *
 * @param model the carrier model the plan is for
 * @param direction up or down
 * @param steps everything to do, in order
 * @param blockers what every engaged interlock says, in the order they were given
 */
public record SequencePlan(String model, Direction direction, List<PowerStep> steps, List<String> blockers) {

    /** How long each rail is left to settle on the way down unless the caller says otherwise. */
    public static final int DEFAULT_DOWN_SETTLE_MS = 2;

    /** Which way a plan takes the board. */
    public enum Direction {
        UP("up"),
        DOWN("down");

        private final String value;

        Direction(String value) {
            this.value = value;
        }

        /** The name the direction is printed under. */
        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public SequencePlan {
        steps = List.copyOf(steps);
        blockers = List.copyOf(blockers);
    }

    /** {@link #powerUp(PowerTree, List, boolean)} with no interlocks, reading every rail back. */
    public static SequencePlan powerUp(PowerTree tree) {
        return powerUp(tree, List.of(), true);
    }

    /** {@link #powerUp(PowerTree, List, boolean)} reading every rail back. */
    public static SequencePlan powerUp(PowerTree tree, List<Interlock> interlocks) {
        return powerUp(tree, interlocks, true);
    }

    /**
     * Bring a board up, rail by rail, in dependency order: enable each rail, let it settle if it
     * takes time to, and read it back unless {@code verify} is off for a board with no monitors.
     */
    public static SequencePlan powerUp(PowerTree tree, List<Interlock> interlocks, boolean verify) {
        List<PowerStep> steps = new ArrayList<>();
        for (RailStep rail : tree.orderUp()) {
            steps.add(new PowerStep(PowerAction.ENABLE, rail.name()));
            if (rail.settleMs() != 0) {
                steps.add(new PowerStep(PowerAction.SETTLE, rail.name(), rail.settleMs()));
            }
            if (verify) {
                steps.add(new PowerStep(PowerAction.CHECK, rail.name()));
            }
        }
        return new SequencePlan(tree.model(), Direction.UP, steps, blockers(interlocks));
    }

    /** {@link #powerDown(PowerTree, List, int)} with no interlocks and the usual settle. */
    public static SequencePlan powerDown(PowerTree tree) {
        return powerDown(tree, List.of(), DEFAULT_DOWN_SETTLE_MS);
    }

    /** {@link #powerDown(PowerTree, List, int)} with the usual settle. */
    public static SequencePlan powerDown(PowerTree tree, List<Interlock> interlocks) {
        return powerDown(tree, interlocks, DEFAULT_DOWN_SETTLE_MS);
    }

    /**
     * Take a board down in the order the datasheets want, disabling each rail and letting it settle.
     *
     * <p>Interlocks apply here too, and that is not an oversight. The one that matters is
     * maintenance: a board somebody has their hands inside is a board whose rails should not move in
     * either direction without them saying so.
     */
    public static SequencePlan powerDown(PowerTree tree, List<Interlock> interlocks, int settleMs) {
        List<PowerStep> steps = new ArrayList<>();
        for (RailStep rail : tree.orderDown()) {
            steps.add(new PowerStep(PowerAction.DISABLE, rail.name()));
            if (settleMs != 0) {
                steps.add(new PowerStep(PowerAction.SETTLE, rail.name(), settleMs));
            }
        }
        return new SequencePlan(tree.model(), Direction.DOWN, steps, blockers(interlocks));
    }

    /** Whether nothing stands in the way. */
    public boolean runnable() {
        return blockers.isEmpty();
    }

    /** How long the whole plan waits, added up. */
    public int totalMs() {
        return steps.stream().mapToInt(PowerStep::millis).sum();
    }

    /** Every rail the plan touches, in the order it first touches them. */
    public List<String> rails() {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        steps.forEach(step -> seen.add(step.rail()));
        return List.copyOf(seen);
    }

    /** The steps of one kind, in order. */
    public List<PowerStep> stepsOf(PowerAction action) {
        return steps.stream().filter(step -> step.action() == action).toList();
    }

    /** {@code tx-carrier-a up: 12 steps, 23ms}, or {@code tx-carrier-a up blocked: lid: rack door open}. */
    public String describe() {
        if (!runnable()) {
            return model + " " + direction + " blocked: " + String.join("; ", blockers);
        }
        return model + " " + direction + ": " + steps.size() + " steps, " + totalMs() + "ms";
    }

    private static List<String> blockers(List<Interlock> interlocks) {
        return interlocks.stream()
                .filter(Interlock::engaged)
                .map(Interlock::describe)
                .toList();
    }
}
