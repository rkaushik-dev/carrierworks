package io.github.rkaushikdev.carrierworks.catalog;

/**
 * One silicon device, as the bench needs to know it.
 *
 * <p>Two kinds of number live here and they are used at opposite ends of the bench. The resource
 * counts are what a utilization report gets compared against, so a design that will not fit is
 * caught before anyone books a board for it. The idcode and the instruction register length are
 * what the scan chain code needs to talk to the device at all, and getting either wrong means the
 * chain reads back as garbage rather than as an error.
 *
 * <p>Configuration bit counts are the uncompressed bitstream length for the part. The flash
 * planner uses them to work out whether a golden and an update image will both sit in the device
 * the board actually carries.
 *
 * @param device the device code, such as {@code xc7a100t}
 * @param family the family, such as {@code artix7}
 * @param idcode what the device answers on the chain, stepping nibble clear
 * @param irLength the instruction register length in bits
 * @param luts lookup tables
 * @param flipFlops flip flops
 * @param blockRam36k 36 kilobit block RAMs
 * @param dspSlices DSP slices
 * @param configBits the uncompressed configuration length in bits
 * @param transceivers serial transceivers
 */
public record DeviceSpec(
        String device,
        String family,
        long idcode,
        int irLength,
        int luts,
        int flipFlops,
        int blockRam36k,
        int dspSlices,
        long configBits,
        int transceivers) {

    /** Uncompressed configuration length, rounded up to whole bytes. */
    public long configBytes() {
        return (configBits + 7) / 8;
    }

    /** The block RAM, in bits. */
    public long blockRamBits() {
        return (long) blockRam36k * 36 * 1024;
    }

    /** Whether a scanned idcode is this device, stepping ignored. */
    public boolean matchesIdcode(long scanned) {
        return (scanned & Devices.IDCODE_MASK) == (idcode & Devices.IDCODE_MASK);
    }

    /**
     * Whether a design of this size fits, ignoring routing.
     *
     * <p>Routing is the reason a design that fits by these numbers still fails to place, so this is
     * a fast no rather than a slow yes.
     */
    public boolean holds(int luts, int flipFlops, int blockRam36k, int dspSlices) {
        return luts <= this.luts
                && flipFlops <= this.flipFlops
                && blockRam36k <= this.blockRam36k
                && dspSlices <= this.dspSlices;
    }
}
