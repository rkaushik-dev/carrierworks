package io.github.rkaushikdev.carrierworks.power;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.PduChannel;
import io.github.rkaushikdev.carrierworks.PowerFault;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A distribution unit that only exists in memory.
 *
 * <p>Used by the tests and by the dry run mode of the command line tool, which is how somebody
 * checks what a power cycle would do to a rack without doing it. Every outlet starts off, an outlet
 * that is on draws {@value #DEFAULT_CURRENT_MA} milliamps unless told otherwise, and every switch is
 * written down as {@code on pdu-a/outlet-07} or {@code off pdu-a/outlet-07}.
 *
 * <p>The {@code with} methods set the fake up and hand back this same fake.
 */
public final class FakePdu implements PduDriver {

    /** What an outlet that is on draws unless the fake is told otherwise. */
    public static final int DEFAULT_CURRENT_MA = 800;

    private final Map<PduChannel, Boolean> outlets = new HashMap<>();
    private final Map<PduChannel, Integer> currents = new HashMap<>();
    private final Set<String> unreachable = new HashSet<>();
    private final List<String> commands = new ArrayList<>();

    /** Start with this outlet already on, without writing a command down. */
    public FakePdu withOutletOn(PduChannel channel) {
        outlets.put(channel, true);
        return this;
    }

    /** Have this outlet draw this much while it is on. */
    public FakePdu withCurrent(PduChannel channel, int milliamps) {
        currents.put(channel, milliamps);
        return this;
    }

    /** Have every outlet on this unit fail as if the unit did not answer. */
    public FakePdu withUnreachable(String pdu) {
        unreachable.add(pdu);
        return this;
    }

    /** Every switch made so far, oldest first. */
    public List<String> commands() {
        return List.copyOf(commands);
    }

    /** Forget the switches made so far. */
    public void clearCommands() {
        commands.clear();
    }

    @Override
    public String name() {
        return "fake-pdu";
    }

    @Override
    public void switchOutlet(PduChannel channel, boolean on) {
        reach(channel);
        outlets.put(channel, on);
        commands.add((on ? "on " : "off ") + channel);
    }

    @Override
    public boolean isOn(PduChannel channel) {
        reach(channel);
        return outlets.getOrDefault(channel, false);
    }

    @Override
    public int currentMa(PduChannel channel) {
        reach(channel);
        if (!outlets.getOrDefault(channel, false)) {
            return 0;
        }
        return currents.getOrDefault(channel, DEFAULT_CURRENT_MA);
    }

    private void reach(PduChannel channel) {
        if (unreachable.contains(channel.pdu())) {
            throw new PowerFault("the distribution unit did not answer", Details.of("pdu", channel.pdu()));
        }
    }
}
