package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Frequency;
import io.github.rkaushikdev.carrierworks.ValidationError;

/**
 * A transport that returns TDI unchanged.
 *
 * <p>Not a simulation of anything, and it will not answer a real scan. It is here so that code
 * paths which only need a transport to exist can have one, and so the recorder has something to
 * wrap in its own tests.
 */
public final class LoopbackTransport implements Transport {

    private Frequency clock = Frequency.megahertz(1);
    private boolean closed;

    @Override
    public String name() {
        return "loopback";
    }

    /** The rate it was last told to run at, one megahertz until then. */
    public Frequency clock() {
        return clock;
    }

    @Override
    public void setClock(Frequency clock) {
        this.clock = clock;
    }

    @Override
    public BitVector pulse(BitVector tms, BitVector tdi) {
        if (closed) {
            throw new ValidationError("transport is closed", "transport");
        }
        if (tms.length() != tdi.length()) {
            throw new ValidationError(
                    "tms and tdi have to be the same length",
                    "pulse",
                    Details.of("tms", tms.length(), "tdi", tdi.length()));
        }
        if (tms.length() == 0) {
            throw new ValidationError("a pulse clocks at least one bit", "pulse");
        }
        return tdi;
    }

    /** Whether it has been closed. */
    public boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }
}
