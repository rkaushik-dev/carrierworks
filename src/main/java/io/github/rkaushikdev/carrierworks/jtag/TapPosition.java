package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.CatalogIndex;
import io.github.rkaushikdev.carrierworks.catalog.DeviceSpec;
import io.github.rkaushikdev.carrierworks.catalog.Devices;
import io.github.rkaushikdev.carrierworks.catalog.IdCode;
import java.util.Optional;

/**
 * One device on the chain, or one thing that has to be shifted past.
 *
 * <p>A position holds an instruction register width whether or not the bench knows what the device
 * is. An unknown part on the chain still has to be shifted past, and the width is the only thing
 * needed to do it.
 *
 * @param label the position's name on the chain, as a slug
 * @param irLength the instruction register width, two to thirty two bits
 * @param device the catalogued device at this position, if the bench stocks it
 * @param expectedIdcode what the position answers a discovery scan with, if known
 */
public record TapPosition(String label, int irLength, Optional<String> device, Optional<Long> expectedIdcode) {

    public TapPosition {
        label = Ids.slug(label);
        if (irLength < 2 || irLength > 32) {
            throw new ValidationError(
                    "an instruction register is between two and thirty two bits",
                    "ir_length",
                    Details.of("given", irLength));
        }
        if (expectedIdcode.isPresent() && (expectedIdcode.get() < 0 || expectedIdcode.get() > IdCode.ALL_ONES)) {
            throw new ValidationError("idcode does not fit in a word", "expected_idcode");
        }
    }

    /** A position the bench knows only by its width. */
    public TapPosition(String label, int irLength) {
        this(label, irLength, Optional.empty(), Optional.empty());
    }

    /** A position that is no catalogued device but answers with a known idcode. */
    public TapPosition(String label, int irLength, long expectedIdcode) {
        this(label, irLength, Optional.empty(), Optional.of(expectedIdcode));
    }

    /** A position whose width and idcode come from the catalog, or a not found for a part nobody stocks. */
    public static TapPosition forDevice(String label, String device) {
        DeviceSpec spec =
                Devices.lookup(device).orElseThrow(() -> new NotFound("device", CatalogIndex.normalise(device)));
        return new TapPosition(label, spec.irLength(), Optional.of(spec.device()), Optional.of(spec.idcode()));
    }

    /** Whether the bench knows which device sits here. */
    public boolean known() {
        return device.isPresent();
    }
}
