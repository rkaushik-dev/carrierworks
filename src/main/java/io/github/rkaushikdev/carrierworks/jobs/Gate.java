package io.github.rkaushikdev.carrierworks.jobs;

/**
 * One check a programming job has to pass, by the name it is reported under.
 *
 * <p>The gates run in this order and every one of them runs, so a refused job hears about all of
 * its problems at once. The names are what the journal records against a refusal, which is how a
 * lab finds out that half its refused jobs are refused by one rule and goes to fix the rule or the
 * habit behind it.
 */
public enum Gate {
    /** Somebody has to hold the board, and it has to be whoever is asking. */
    LEASE("lease", false),
    /** The board has to be in a state that can be programmed. */
    STATE("state", false),
    /** The image has to be for the device that is fitted. */
    DEVICE("device", false),
    /** A load over JTAG cannot be longer than the device's configuration. */
    CONFIGURATION("configuration", false),
    /** The image has to be in a channel the mode takes images from. */
    CHANNEL("channel", false),
    /** A partial image never goes to flash. */
    PARTIAL("partial", true),
    /** The image has to fit the part. */
    CAPACITY("capacity", true),
    /** A flash write wants a timing report that says the design closed. */
    TIMING("timing", true),
    /** A worn flash does not get the golden image. */
    WEAR("wear", true),
    /** A quad read needs a part wired for quad. */
    BUS_WIDTH("bus-width", true),
    /** A golden image that jumps has to land on the update image, and an update image does not jump. */
    JUMP("jump", true),
    /** A three byte read cannot see past sixteen megabytes. */
    REACH("reach", true),
    /** A flash write needs a cable that can drive one. */
    CABLE("cable", true);

    private final String value;
    private final boolean flashOnly;

    Gate(String value, boolean flashOnly) {
        this.value = value;
        this.flashOnly = flashOnly;
    }

    /** The name the gate is reported and journalled under. */
    public String value() {
        return value;
    }

    /** Whether the gate is about the configuration flash, and so only runs for a flash write. */
    public boolean flashOnly() {
        return flashOnly;
    }

    @Override
    public String toString() {
        return value;
    }
}
