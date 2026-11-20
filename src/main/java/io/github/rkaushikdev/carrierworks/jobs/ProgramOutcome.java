package io.github.rkaushikdev.carrierworks.jobs;

import java.util.Objects;

/**
 * What one load over JTAG did.
 *
 * @param board the board the load is reported against
 * @param label the position on the chain that was loaded
 * @param bytesSent how many bytes of image went in
 * @param clocks how many clocks the load took, its opening reset included
 * @param done whether the device came up configured
 * @param status the status word read back at the end
 */
public record ProgramOutcome(String board, String label, long bytesSent, long clocks, boolean done, long status) {

    public ProgramOutcome {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(label, "label");
    }

    /** The board and label, then the bytes sent, the clocks taken and {@code done} or {@code not done}. */
    public String describe() {
        String state = done ? "done" : "not done";
        return board + " " + label + ": " + bytesSent + " bytes, " + clocks + " clocks, " + state;
    }
}
