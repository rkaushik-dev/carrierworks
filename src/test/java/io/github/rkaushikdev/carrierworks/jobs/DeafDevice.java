package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.jtag.BitVector;
import io.github.rkaushikdev.carrierworks.jtag.SimulatedDevice;

/** A device that takes the image and never comes up, like a bad cable. */
final class DeafDevice extends SimulatedDevice {

    DeafDevice(String label, long idcode, int irLength) {
        super(label, idcode, irLength);
    }

    @Override
    public void takeConfig(BitVector written) {
        // The image goes nowhere, so jstart has nothing to start and done never comes up.
    }
}
