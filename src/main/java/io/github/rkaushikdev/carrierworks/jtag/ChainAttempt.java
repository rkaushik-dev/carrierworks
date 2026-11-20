package io.github.rkaushikdev.carrierworks.jtag;

import java.util.Optional;

/**
 * One scan and what came of it.
 *
 * @param index which attempt this was, counting from zero
 * @param capture the bits that came back, or empty when the scan itself failed
 * @param error what was wrong with the chain, or empty when it was the chain on file
 */
public record ChainAttempt(int index, Optional<BitVector> capture, Optional<String> error) {

    /** Whether this scan read back the chain on file. */
    public boolean ok() {
        return error.isEmpty();
    }
}
