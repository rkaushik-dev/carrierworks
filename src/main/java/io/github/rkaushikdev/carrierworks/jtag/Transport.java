package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.Frequency;

/**
 * Whatever can clock bits at a chain: the boundary between everything above and the cable driver
 * underneath.
 *
 * <p>The interface is deliberately one call wide. A transport clocks a run of TMS and TDI bits and
 * hands back what came out of TDO, and it does not know what a chain is, what an instruction is, or
 * which device anything belongs to. Every driver the lab has ever had could be written against
 * that, including the two that talk to a vendor tool over a pipe rather than to a cable.
 */
public interface Transport {

    /** A short name for logs and transcripts. */
    String name();

    /** Run TCK at this rate from now on. */
    void setClock(Frequency clock);

    /**
     * Clock one run of bits, TMS and TDI side by side and bit zero first, and return what TDO gave
     * back on the same clocks.
     */
    BitVector pulse(BitVector tms, BitVector tdi);

    /** Let go of the cable. */
    void close();
}
