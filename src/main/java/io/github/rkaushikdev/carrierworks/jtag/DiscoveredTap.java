package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.catalog.IdCode;
import java.util.Optional;

/**
 * One device a discovery scan found, in the order the bits arrived.
 *
 * @param idcode what it answered with, or empty for a device that sat in bypass
 * @param device the catalogued device that idcode belongs to, if the bench stocks it
 * @param irLength that device's instruction register width, if the bench stocks it
 */
public record DiscoveredTap(Optional<IdCode> idcode, Optional<String> device, Optional<Integer> irLength) {

    /** A device that had no idcode to give and answered with a single bypass bit. */
    public static DiscoveredTap bypass() {
        return new DiscoveredTap(Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** Whether the device answered in bypass rather than with an idcode. */
    public boolean inBypass() {
        return idcode.isEmpty();
    }

    /** Whether the catalog knows the device. */
    public boolean recognised() {
        return device.isPresent();
    }

    /** {@code bypass}, {@code xc7a100t 0x03631093} or {@code unknown 0x01234093}. */
    public String describe() {
        return idcode.map(code -> device.orElse("unknown") + " " + code).orElse("bypass");
    }
}
