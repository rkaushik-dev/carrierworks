package io.github.rkaushikdev.carrierworks.jobs;

import java.util.Objects;

/**
 * Which device on a chain an image goes into, and how.
 *
 * @param label the position on the chain to load
 * @param board the board to report the load against; empty reports it against the label
 * @param swap whether each byte is bit reversed on the way in, which is what the configuration port
 *     wants for an image laid out the way the tools write one
 */
public record JtagLoad(String label, String board, boolean swap) {

    /** The position a single device chain labels its device with. */
    public static final String DEFAULT_LABEL = "u1";

    public JtagLoad {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(board, "board");
    }

    /** The first device on the chain, reported against its label, bytes bit reversed. */
    public static JtagLoad defaults() {
        return new JtagLoad(DEFAULT_LABEL, "", true);
    }

    /** The same load into another position on the chain. */
    public JtagLoad withLabel(String value) {
        return new JtagLoad(value, board, swap);
    }

    /** The same load reported against a board. */
    public JtagLoad withBoard(String value) {
        return new JtagLoad(label, value, swap);
    }

    /** The same load with the bit reversal on or off. */
    public JtagLoad withSwap(boolean value) {
        return new JtagLoad(label, board, value);
    }
}
