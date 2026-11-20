package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.bitstream.Binary;
import io.github.rkaushikdev.carrierworks.catalog.FlashSpec;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * A part that behaves like flash, including the awkward part.
 *
 * <p>This is not a stub. It models the one behaviour that makes flash different from memory:
 * programming can only turn ones into zeros. A write into a sector that was not erased first
 * produces the bitwise and of what was there and what was sent, which is neither image and looks
 * like a corrupt file rather than like a missing erase. That is the bug this fake exists to catch,
 * and it caught it once already in the plan builder.
 *
 * <p>It can also be told that some bytes are weak, which is how a part near the end of its life
 * fails: they read back erased whatever was programmed into them.
 */
public final class FakeFlash implements SpiFlashDriver {

    private final FlashSpec spec;
    private final byte[] storage;
    private int erases;
    private int writes;
    private Set<Long> weakBytes = Set.of();

    /** A fresh part, erased from end to end. */
    public FakeFlash(FlashSpec spec) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.storage = new byte[Math.toIntExact(spec.capacityBytes())];
        Arrays.fill(storage, (byte) Binary.ERASED);
    }

    /** The part it pretends to be. */
    public FlashSpec spec() {
        return spec;
    }

    /** How many erases it has been asked for. */
    public int erases() {
        return erases;
    }

    /** How many page programs it has been asked for. */
    public int writes() {
        return writes;
    }

    /** The addresses that do not hold what is programmed into them. */
    public Set<Long> weakBytes() {
        return weakBytes;
    }

    /** Make these addresses, and only these, read back erased whatever is programmed into them. */
    public void setWeakBytes(Set<Long> addresses) {
        weakBytes = Set.copyOf(addresses);
    }

    /**
     * Put bytes straight into the part, past the programming rules, the way a part arrives already
     * holding something.
     */
    public void preload(long address, byte[] data) {
        spec.checkAddress(address);
        System.arraycopy(data, 0, storage, index(address), data.length);
    }

    @Override
    public int readId() {
        return spec.jedecId();
    }

    /**
     * Erase back to all ones.
     *
     * @throws ValidationError when the address is outside the part or not on a subsector
     */
    @Override
    public void erase(long address, long length) {
        spec.checkAddress(address);
        if (address % spec.subsectorBytes() != 0) {
            throw new ValidationError("an erase starts on a subsector", "address", Details.of("given", address));
        }
        Arrays.fill(storage, index(address), end(address, length), (byte) Binary.ERASED);
        erases++;
    }

    /**
     * Program one page, clearing bits and never setting them.
     *
     * @throws ValidationError when the address is outside the part, the data is longer than a page,
     *     or the write would cross into the next page
     */
    @Override
    public void program(long address, byte[] data) {
        spec.checkAddress(address);
        if (data.length > spec.pageBytes()) {
            throw new ValidationError("a program is one page at most", "data", Details.of("given", data.length));
        }
        if (address % spec.pageBytes() + data.length > spec.pageBytes()) {
            throw new ValidationError("a page write cannot cross a page", "address");
        }
        for (int offset = 0; offset < data.length; offset++) {
            long at = address + offset;
            // Programming clears bits and never sets them, which is the whole reason an erase has
            // to come first.
            byte written = (byte) (storage[index(at)] & data[offset]);
            storage[index(at)] = weakBytes.contains(at) ? (byte) Binary.ERASED : written;
        }
        writes++;
    }

    /**
     * Read what the part holds, stopping at its end.
     *
     * @throws ValidationError when the address is outside the part
     */
    @Override
    public byte[] read(long address, int length) {
        spec.checkAddress(address);
        return Arrays.copyOfRange(storage, index(address), end(address, length));
    }

    private static int index(long address) {
        return Math.toIntExact(address);
    }

    /** Where a span starting at an address stops, which is never past the end of the part. */
    private int end(long address, long length) {
        return index(Math.min(address + length, storage.length));
    }
}
