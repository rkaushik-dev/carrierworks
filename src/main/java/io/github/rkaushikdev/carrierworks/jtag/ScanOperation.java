package io.github.rkaushikdev.carrierworks.jtag;

/**
 * One clocked run, kept for the journal.
 *
 * @param tms what went out on TMS
 * @param tdi what went out on TDI
 * @param tdo what came back on TDO
 */
public record ScanOperation(BitVector tms, BitVector tdi, BitVector tdo) {

    /** How many clocks the run took. */
    public int clocks() {
        return tms.length();
    }

    /** {@code 2 clocks tms=11 tdi=01 tdo=01}. */
    public String describe() {
        return clocks() + " clocks tms=" + tms + " tdi=" + tdi + " tdo=" + tdo;
    }
}
