package io.github.rkaushikdev.carrierworks.power;

import io.github.rkaushikdev.carrierworks.PduChannel;
import io.github.rkaushikdev.carrierworks.PowerFault;

/**
 * Whatever can switch an outlet.
 *
 * <p>The distribution units in the lab speak three different protocols and one of them is a telnet
 * session that drops if you look at it. None of that belongs anywhere near the scheduler, so the
 * interface is four calls wide and every driver behind it is somebody else's problem. A driver that
 * cannot reach its unit raises a {@link PowerFault}, which is worth retrying.
 */
public interface PduDriver {

    /** Which driver this is, for the logs. */
    String name();

    /** Switch one outlet on or off. */
    void switchOutlet(PduChannel channel, boolean on);

    /** Whether one outlet is on. */
    boolean isOn(PduChannel channel);

    /** What one outlet is drawing, in milliamps at the wall. */
    int currentMa(PduChannel channel);
}
