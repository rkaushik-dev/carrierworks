package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.Ids;

/**
 * A particular cable, with the serial burned into it.
 *
 * @param serial the serial, upper case
 * @param model the model, which has to be one the lab stocks
 * @param port where it is plugged in, or empty
 */
public record AdapterUnit(String serial, String model, String port) {

    public AdapterUnit {
        serial = Ids.adapterSerial(serial);
        model = Ids.slug(model);
        Adapters.model(model);
    }

    /** A cable whose port nobody wrote down. */
    public AdapterUnit(String serial, String model) {
        this(serial, model, "");
    }

    /** The model this cable is. */
    public AdapterSpec spec() {
        return Adapters.model(model);
    }

    /** {@code jtag-hs2 210308AB1234 on usb-3.1}. */
    public String describe() {
        String where = port.isEmpty() ? "" : " on " + port;
        return model + " " + serial + where;
    }
}
