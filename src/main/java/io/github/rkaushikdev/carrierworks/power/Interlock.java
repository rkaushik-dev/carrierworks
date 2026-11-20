package io.github.rkaushikdev.carrierworks.power;

import io.github.rkaushikdev.carrierworks.Ids;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * A condition that has to be clear before power is touched.
 *
 * <p>Interlocks are checked once, before the first step, and any of them being engaged means
 * nothing runs. They are not steps because they are not things a sequence does, they are
 * conditions it needs, and mixing the two is how a sequence ends up half executed with a rail up and
 * an interlock engaged.
 *
 * @param name the interlock, as a slug
 * @param engaged whether it is holding power back
 * @param reason why, or empty
 */
public record Interlock(String name, boolean engaged, String reason) {

    /** The reason an interlock gives when its check could not be read. */
    public static final String UNREADABLE = "could not be read";

    public Interlock {
        name = Ids.slug(name);
    }

    /** An interlock with nothing to say about why. */
    public Interlock(String name, boolean engaged) {
        this(name, engaged, "");
    }

    /** {@link #fromChecks(Map, Map)} with no reasons given. */
    public static List<Interlock> fromChecks(Map<String, BooleanSupplier> checks) {
        return fromChecks(checks, Map.of());
    }

    /**
     * Interlocks built by asking each check whether it is engaged, in the order the checks are given.
     *
     * <p>A check that fails is engaged, not clear, with the reason {@value #UNREADABLE}. The
     * supervisor cannot read the lid switch on one of the racks while its controller is rebooting,
     * and the safe answer to that is that the lid is open.
     *
     * @param checks each interlock's name and how to read it
     * @param reasons each interlock's reason by name, kept whether or not it reads as engaged
     */
    public static List<Interlock> fromChecks(Map<String, BooleanSupplier> checks, Map<String, String> reasons) {
        List<Interlock> built = new ArrayList<>();
        for (Map.Entry<String, BooleanSupplier> check : checks.entrySet()) {
            boolean engaged;
            try {
                engaged = check.getValue().getAsBoolean();
            } catch (RuntimeException unreadable) {
                built.add(new Interlock(check.getKey(), true, UNREADABLE));
                continue;
            }
            built.add(new Interlock(check.getKey(), engaged, reasons.getOrDefault(check.getKey(), "")));
        }
        return List.copyOf(built);
    }

    /** {@code lid: rack door open}, or the name alone when there is no reason. */
    public String describe() {
        return reason.isEmpty() ? name : name + ": " + reason;
    }
}
