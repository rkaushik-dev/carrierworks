package io.github.rkaushikdev.carrierworks.jobs;

/**
 * Whatever can talk to the flash beside a device.
 *
 * <p>Four calls, because that is all SPI flash does: say who you are, erase a sector, program a
 * page, read anything. Everything else the bench needs is arithmetic on top of those, and the
 * arithmetic is already in the flash plan.
 */
public interface SpiFlashDriver {

    /** The JEDEC id the part answers with. */
    int readId();

    /** Erase {@code length} bytes starting at {@code address}, back to all ones. */
    void erase(long address, long length);

    /** Program one page, or the part of one the data covers. */
    void program(long address, byte[] data);

    /** Read {@code length} bytes starting at {@code address}. */
    byte[] read(long address, int length);
}
