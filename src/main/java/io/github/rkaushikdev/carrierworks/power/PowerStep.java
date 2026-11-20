package io.github.rkaushikdev.carrierworks.power;

import io.github.rkaushikdev.carrierworks.ValidationError;

/**
 * One thing to do to one rail.
 *
 * <p>The steps are small and boring on purpose. Enable a rail, wait for it to settle, read it back,
 * move on. The read back is the step people leave out: a rail that has been enabled is not a rail
 * that is up, it is a rail whose regulator has been told to start.
 *
 * @param action what to do
 * @param rail the rail to do it to
 * @param millis how long the step takes, which only a settle has
 */
public record PowerStep(PowerAction action, String rail, int millis) {

    public PowerStep {
        if (millis < 0) {
            throw new ValidationError("a step takes no negative time", "millis");
        }
    }

    /** A step that takes no time of its own. */
    public PowerStep(PowerAction action, String rail) {
        this(action, rail, 0);
    }

    /** {@code enable vccint}, or {@code settle vccint (8ms)}. */
    public String describe() {
        if (millis != 0) {
            return action + " " + rail + " (" + millis + "ms)";
        }
        return action + " " + rail;
    }
}
