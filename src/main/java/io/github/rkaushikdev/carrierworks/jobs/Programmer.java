package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.BenchError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.bitstream.Binary;
import io.github.rkaushikdev.carrierworks.jtag.BitVector;
import io.github.rkaushikdev.carrierworks.jtag.ScanSession;
import java.util.Locale;

/**
 * Loading an image into a device over JTAG.
 *
 * <p>The sequence is short and every step of it is in the configuration user guide, which is worth
 * saying because it looks like magic numbers otherwise.
 *
 * <p>JPROGRAM clears whatever was in the device and starts the internal initialisation. It takes
 * time, so the sequence idles afterwards rather than racing on. CFG_IN opens the configuration port
 * and the image is shifted in as one long data register write, most significant bit of each byte
 * first, which is why the bytes go through the bit reverser on the way. JSTART runs the startup
 * sequence, which needs clocks rather than time. Then the status register is read through CFG_OUT
 * and the done bit says whether any of it worked.
 *
 * <p>Done not coming up is the interesting failure. It nearly always means the image was for
 * another device, and the bench has already checked that, so if it happens here the next thing to
 * suspect is the cable.
 */
public final class Programmer {

    // The startup sequence needs clocks after jstart; the guide asks for at least a dozen and the
    // lab has always used this many.
    private static final int STARTUP_CLOCKS = 32;
    private static final int INIT_CLOCKS = 64;
    private static final long DONE_BIT = 1L << 14;
    private static final int STATUS_BITS = 32;

    private Programmer() {}

    /** Put an image into the first device on the chain, bit reversed, and report what happened. */
    public static ProgramOutcome loadOverJtag(ScanSession session, byte[] image) {
        return loadOverJtag(session, image, JtagLoad.defaults());
    }

    /**
     * Put an image into one device on the chain and report what happened.
     *
     * @throws BenchError when there is nothing to load
     * @throws io.github.rkaushikdev.carrierworks.NotFound when the chain has no position with that
     *     label
     * @throws ProgrammingFailed when the done bit does not come up, which is worth another go
     */
    public static ProgramOutcome loadOverJtag(ScanSession session, byte[] image, JtagLoad load) {
        if (image.length == 0) {
            throw new BenchError("nothing to load");
        }
        String label = load.label();
        session.chain().indexOf(label);
        long before = session.clocks();
        session.reset();
        session.loadInstruction(label, "jprogram");
        session.runTestIdle(INIT_CLOCKS);
        session.loadInstruction(label, "cfg_in");
        byte[] payload = load.swap() ? Binary.reverseBits(image) : image;
        session.shiftDr(label, BitVector.fromBytes(payload));
        session.loadInstruction(label, "jstart");
        session.runTestIdle(STARTUP_CLOCKS);
        session.loadInstruction(label, "cfg_out");
        long status = session.shiftDr(label, BitVector.zeros(STATUS_BITS)).toLong();
        boolean done = (status & DONE_BIT) != 0;
        ProgramOutcome outcome = new ProgramOutcome(
                load.board().isEmpty() ? label : load.board(),
                label,
                image.length,
                session.clocks() - before,
                done,
                status);
        if (!done) {
            throw new ProgrammingFailed(
                    "the device did not come up configured",
                    Details.of(
                            "board",
                            outcome.board(),
                            "status",
                            String.format(Locale.ROOT, "0x%08x", status),
                            "bytes_sent",
                            outcome.bytesSent()));
        }
        return outcome;
    }
}
