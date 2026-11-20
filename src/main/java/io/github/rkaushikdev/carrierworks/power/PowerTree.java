package io.github.rkaushikdev.carrierworks.power;

import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.RailSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Every rail on one carrier, and the order they are allowed in.
 *
 * <p>Sequencing is not a preference. The device datasheets say core before auxiliary before the
 * banks, and a board that brings its banks up first can pull current through the input protection
 * diodes of a device whose core is still at zero. Nothing dramatic happens the first time. It
 * shortens the life of the part.
 *
 * <p>So the tree is declared with dependencies rather than with numbers. Each rail names the rails
 * that have to be up before it, and the order is computed from that. Numbers get edited by whoever
 * is adding a rail and quietly stop meaning what they used to; dependencies stay true.
 *
 * <p>Powering down runs the same order backwards, with one exception the transceiver rails need:
 * the analogue supplies come down before the core, because leaving them up over a dead core is the
 * case the datasheet actually warns about.
 *
 * @param model the carrier model, as a slug
 * @param steps every rail, in declaration order
 */
public record PowerTree(String model, List<RailStep> steps) {

    private static final Map<String, PowerTree> TREES = Stream.of(
                    new PowerTree(
                            "rx-carrier-b",
                            List.of(
                                    new RailStep(rail("vccint", 1000, 12000), List.of(), 10),
                                    new RailStep(rail("vccbram", 1000, 2000), List.of("vccint")),
                                    new RailStep(rail("vccaux", 1800, 3000), List.of("vccint"), 8),
                                    new RailStep(rail("vcco33", 3300, 4000), List.of("vccaux")),
                                    new RailStep(rail("vadj", 1800, 2000), List.of("vccaux"), 12),
                                    new RailStep(rail("mgtavcc", 1000, 3000), List.of("vccint"), 6, true),
                                    new RailStep(rail("mgtavtt", 1200, 3000), List.of("mgtavcc"), 6, true))),
                    new PowerTree(
                            "tx-carrier-a",
                            List.of(
                                    new RailStep(rail("vccint", 1000, 4000), List.of(), 8),
                                    new RailStep(rail("vccaux", 1800, 1500), List.of("vccint")),
                                    new RailStep(rail("vcco33", 3300, 2000), List.of("vccaux")),
                                    new RailStep(rail("vadj", 1800, 1000), List.of("vccaux")))))
            .collect(Collectors.toUnmodifiableMap(PowerTree::model, Function.identity()));

    /**
     * A tree, checked: it has rails, their names are unique, every dependency is on the board, and
     * the dependencies can be ordered.
     */
    public PowerTree {
        model = Ids.slug(model);
        steps = List.copyOf(steps);
        List<String> names = steps.stream().map(RailStep::name).toList();
        if (names.isEmpty()) {
            throw new ConfigurationError("a board has rails", Details.of("model", model));
        }
        Set<String> known = new HashSet<>(names);
        if (known.size() != names.size()) {
            throw new ConfigurationError("two rails share a name", Details.of("model", model, "rails", names));
        }
        for (RailStep step : steps) {
            List<String> unknown = step.dependsOn().stream()
                    .filter(name -> !known.contains(name))
                    .toList();
            if (!unknown.isEmpty()) {
                throw new ConfigurationError(
                        "a rail depends on something that is not on this board",
                        Details.of("model", model, "rail", step.name(), "missing", String.join(", ", unknown)));
            }
        }
        ordered(model, steps);
    }

    /**
     * The power tree for a carrier model, however the model was written.
     *
     * @throws NotFound when nobody has described that carrier's rails
     */
    public static PowerTree forModel(String model) {
        String key = Ids.slug(model);
        PowerTree found = TREES.get(key);
        if (found == null) {
            throw new NotFound("power tree", key);
        }
        return found;
    }

    /** One rail by name, or a not found. */
    public RailStep step(String name) {
        String wanted = Ids.slug(name);
        return steps.stream()
                .filter(step -> step.name().equals(wanted))
                .findFirst()
                .orElseThrow(() -> new NotFound("rail", wanted));
    }

    /**
     * The rails in the order they may be enabled.
     *
     * <p>A plain topological walk: each round takes, in declaration order, every rail whose
     * dependencies were all up before the round began, so the same tree always produces the same
     * sequence.
     */
    public List<RailStep> orderUp() {
        return ordered(model, steps);
    }

    /** The order to switch off in: the rails marked to come down first, then the rest, each reversed. */
    public List<RailStep> orderDown() {
        List<RailStep> up = orderUp().reversed();
        List<RailStep> down =
                new ArrayList<>(up.stream().filter(RailStep::downFirst).toList());
        down.addAll(up.stream().filter(step -> !step.downFirst()).toList());
        return List.copyOf(down);
    }

    /** How long every rail takes to settle, added up. */
    public int totalSettleMs() {
        return steps.stream().mapToInt(RailStep::settleMs).sum();
    }

    /** The rail names, in declaration order. */
    public List<String> names() {
        return steps.stream().map(RailStep::name).toList();
    }

    /** {@code tx-carrier-a: vccint -> vccaux -> vcco33 -> vadj}. */
    public String describe() {
        return model + ": " + orderUp().stream().map(RailStep::name).collect(Collectors.joining(" -> "));
    }

    private static List<RailStep> ordered(String model, List<RailStep> steps) {
        List<RailStep> ordered = new ArrayList<>();
        Set<String> done = new HashSet<>();
        List<RailStep> remaining = new ArrayList<>(steps);
        while (!remaining.isEmpty()) {
            List<RailStep> ready = remaining.stream()
                    .filter(step -> done.containsAll(step.dependsOn()))
                    .toList();
            if (ready.isEmpty()) {
                String stuck = remaining.stream().map(RailStep::name).collect(Collectors.joining(", "));
                throw new ConfigurationError(
                        "these rails depend on each other", Details.of("model", model, "rails", stuck));
            }
            for (RailStep step : ready) {
                ordered.add(step);
                done.add(step.name());
                remaining.remove(step);
            }
        }
        return List.copyOf(ordered);
    }

    private static RailSpec rail(String name, int millivolts, int currentMa) {
        return new RailSpec(name, millivolts, 5, currentMa);
    }
}
