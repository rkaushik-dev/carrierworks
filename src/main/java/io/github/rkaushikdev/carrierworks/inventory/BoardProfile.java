package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.CapabilityProfile;
import io.github.rkaushikdev.carrierworks.RequirementSet;
import io.github.rkaushikdev.carrierworks.catalog.CardSpec;
import io.github.rkaushikdev.carrierworks.catalog.DeviceSpec;
import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A board turned into the thing a job's requirements are matched against.
 *
 * <p>The scheduler does not know what a carrier is. It knows tokens, counts and attributes, and
 * this is where a board becomes those. Everything a card provides becomes a token, everything the
 * device has becomes a count, and the facts that identify the board become attributes.
 *
 * <p>Two of these are not simply copied off the board. The free transceiver count is what a job
 * can actually use, not what the device has, because a fitted card has already taken some: a job
 * asking for four lanes on a Kintex with sixteen is still refused if the cards on that board have
 * taken thirteen of them. And the {@code power-controlled} token only appears when the board has
 * an outlet the bench can switch. Half the boards in the lab are plugged into a wall strip, and a
 * job that needs to power cycle its board cannot run on one of those however well it matches on
 * everything else.
 */
public final class BoardProfile {

    private static final long MIB = 1024L * 1024L;
    private static final int MHZ = 1_000_000;

    private BoardProfile() {}

    /**
     * Everything about a board that a requirement can ask about.
     *
     * <p>Tokens come back sorted; counts and attributes in a fixed order.
     */
    public static CapabilityProfile of(Board board) {
        DeviceSpec device = board.device();
        FlashSpec flash = board.flashSpec();
        Set<String> tokens = new TreeSet<>();
        tokens.add("jtag");
        for (CardSpec spec : board.fittedCards()) {
            tokens.addAll(spec.provides());
        }
        board.adapter().ifPresent(adapter -> {
            tokens.add("cabled");
            if (adapter.spec().canProgramFlash()) {
                tokens.add("flash-programmable");
            }
        });
        if (board.pdu().isPresent()) {
            tokens.add("power-controlled");
        }
        if (!board.emptySlots().isEmpty()) {
            tokens.add("spare-slot");
        }
        Map<String, Integer> numbers = new LinkedHashMap<>();
        numbers.put("luts", device.luts());
        numbers.put("flip-flops", device.flipFlops());
        numbers.put("block-ram", device.blockRam36k());
        numbers.put("dsp", device.dspSlices());
        numbers.put("transceivers", board.transceiversFree());
        numbers.put("transceivers-fitted", device.transceivers());
        numbers.put("flash-mib", Math.toIntExact(flash.capacityBytes() / MIB));
        numbers.put("tck-mhz", board.maxTckHz() / MHZ);
        numbers.put("cards", board.fittedCards().size());
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("family", device.family());
        attributes.put("device", device.device());
        attributes.put("part", board.part().orderingCode());
        attributes.put("package", board.part().packageCode());
        attributes.put("speed", board.part().speedGrade());
        attributes.put("model", board.model());
        attributes.put("revision", board.revision());
        attributes.put("rack", board.location().rack());
        attributes.put("vadj", Integer.toString(board.vadjMillivolts()));
        attributes.put("flash", flash.name());
        attributes.put("asset", board.tag());
        return CapabilityProfile.build(tokens, numbers, attributes);
    }

    /** Whether a board can do what a job asked for. */
    public static boolean matches(Board board, RequirementSet wanted) {
        return wanted.satisfiedBy(of(board));
    }

    /** The line an operator reads when their job will not schedule on this board. */
    public static String whyNot(Board board, RequirementSet wanted) {
        return wanted.whyNot(of(board));
    }
}
