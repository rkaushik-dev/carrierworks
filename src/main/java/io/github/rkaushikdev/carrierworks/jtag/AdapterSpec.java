package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.CatalogError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Frequency;
import io.github.rkaushikdev.carrierworks.Ids;
import java.util.Collections;
import java.util.List;

/**
 * One model of programming cable.
 *
 * <p>Voltage is not negotiable the way the clock is. The adapter drives its outputs at whatever
 * the board tells it to on the reference pin, and an adapter that cannot reach that level is simply
 * the wrong cable. Finding that out in software beats finding it out by watching a chain read back
 * as noise.
 *
 * @param model the model, as a slug
 * @param vendor who makes it
 * @param maxClockHz the clock on the box
 * @param voltagesMv the bank levels it can drive
 * @param channels how many JTAG channels it has
 * @param buffered whether its outputs are buffered
 * @param canProgramFlash whether it can drive an indirect flash write
 */
public record AdapterSpec(
        String model,
        String vendor,
        int maxClockHz,
        List<Integer> voltagesMv,
        int channels,
        boolean buffered,
        boolean canProgramFlash) {

    public AdapterSpec {
        model = Ids.slug(model);
        voltagesMv = List.copyOf(voltagesMv);
        if (maxClockHz <= 0) {
            throw new CatalogError("an adapter has a clock", Details.of("adapter", model));
        }
        if (voltagesMv.isEmpty()) {
            throw new CatalogError("an adapter drives some voltage", Details.of("adapter", model));
        }
        if (channels < 1) {
            throw new CatalogError("an adapter has at least one channel", Details.of("adapter", model));
        }
    }

    /** A single channel, buffered cable that can program flash. */
    public AdapterSpec(String model, String vendor, int maxClockHz, List<Integer> voltagesMv) {
        this(model, vendor, maxClockHz, voltagesMv, 1, true, true);
    }

    /** The clock on the box. */
    public Frequency maxClock() {
        return new Frequency(maxClockHz);
    }

    /** Whether this cable can drive a bank at that level. */
    public boolean drives(int millivolts) {
        return voltagesMv.contains(millivolts);
    }

    /** The lowest level it can drive. */
    public int lowestVoltageMv() {
        return Collections.min(voltagesMv);
    }
}
