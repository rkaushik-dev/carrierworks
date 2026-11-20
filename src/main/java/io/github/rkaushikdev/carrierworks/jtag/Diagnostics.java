package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.BenchError;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reading a chain more than once, because the interesting faults are the ones that come and go.
 *
 * <p>A single scan answers one question: is the chain right now what the inventory says. That is
 * enough to decide whether to program a board and not enough to decide anything about the board
 * itself. A header with one cracked joint reads perfectly two times in three, and the board goes
 * back into service, and the next regression fails somewhere entirely unrelated.
 *
 * <p>So the diagnosis here scans several times and reports the shape of what it saw rather than a
 * verdict on any one scan.
 */
public final class Diagnostics {

    /** How many scans a diagnosis makes unless told otherwise. */
    public static final int DEFAULT_ATTEMPTS = 3;

    private Diagnostics() {}

    /** Scan the session's own chain {@link #DEFAULT_ATTEMPTS} times. */
    public static ChainDiagnosis diagnoseChain(ScanSession session) {
        return diagnoseChain(session, session.chain(), DEFAULT_ATTEMPTS);
    }

    /** Scan the session's own chain {@code attempts} times. */
    public static ChainDiagnosis diagnoseChain(ScanSession session, int attempts) {
        return diagnoseChain(session, session.chain(), attempts);
    }

    /** Scan {@link #DEFAULT_ATTEMPTS} times against a chain other than the session's own. */
    public static ChainDiagnosis diagnoseChain(ScanSession session, ScanChain expected) {
        return diagnoseChain(session, expected, DEFAULT_ATTEMPTS);
    }

    /**
     * Scan a chain {@code attempts} times and report the pattern rather than a verdict.
     *
     * <p>Each scan resets the chain first. A scan that reads back as a wiring fault, does not match
     * the expected chain, or fails outright is recorded with the complaint and the diagnosis carries
     * on to the next one.
     *
     * @throws IllegalArgumentException when asked for fewer than one scan, which is a bug in the
     *     caller rather than anything about the chain
     */
    public static ChainDiagnosis diagnoseChain(ScanSession session, ScanChain expected, int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("a diagnosis needs at least one scan");
        }
        List<ChainAttempt> results = new ArrayList<>(attempts);
        for (int index = 0; index < attempts; index++) {
            Optional<BitVector> capture = Optional.empty();
            Optional<String> error;
            try {
                capture = Optional.of(session.scanIdcodes());
                error = Verify.diagnoseCapture(capture.get());
                if (error.isEmpty()) {
                    Verify.verifyChain(expected, capture.get());
                }
            } catch (BenchError failure) {
                error = Optional.of(failure.message());
            }
            results.add(new ChainAttempt(index, capture, error));
        }
        return new ChainDiagnosis(expected.describe(), results);
    }
}
