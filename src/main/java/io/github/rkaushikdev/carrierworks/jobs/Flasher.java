package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.flash.FlashPlan;
import io.github.rkaushikdev.carrierworks.flash.FlashStep;
import java.util.Arrays;
import java.util.Locale;

/**
 * Writing an image into the configuration flash, and reading it back.
 *
 * <p>The plan already holds the arithmetic; this runs it against a {@link SpiFlashDriver} and
 * checks what it left behind.
 *
 * <p>Verification reads the image back and compares it. It is not optional in practice: a flash
 * near the end of its life fails to hold what was written to it, and the whole point of the wear
 * ledger is knowing which parts those are.
 */
public final class Flasher {

    private Flasher() {}

    /**
     * Run a flash plan against a driver and check what it left behind.
     *
     * @param start the address the plan's region starts at, which is where the image's first byte
     *     goes
     * @throws ValidationError when the plan was built for an image of another length or for another
     *     part
     * @throws VerifyFailed when a chunk read back differs from what was written
     */
    public static FlashOutcome writeImage(SpiFlashDriver driver, FlashPlan plan, byte[] data, long start) {
        if (data.length != plan.imageBytes()) {
            throw new ValidationError(
                    "the plan was built for a different image",
                    "data",
                    Details.of("planned", plan.imageBytes(), "given", data.length));
        }
        int found = driver.readId();
        if (found != plan.part().jedecId()) {
            throw new ValidationError(
                    "that is not the part the plan was built for",
                    "flash",
                    Details.of("expected", jedec(plan.part().jedecId()), "found", jedec(found)));
        }
        int erases = 0;
        int pages = 0;
        for (FlashStep step : plan.steps()) {
            switch (step.kind()) {
                case ERASE -> {
                    driver.erase(step.address(), step.length());
                    erases++;
                }
                case PROGRAM -> {
                    driver.program(step.address(), slice(data, step.address() - start, step.length()));
                    pages++;
                }
                case VERIFY -> {
                    byte[] wanted = slice(data, step.address() - start, step.length());
                    byte[] read = driver.read(step.address(), Math.toIntExact(step.length()));
                    if (!Arrays.equals(read, wanted)) {
                        throw new VerifyFailed(
                                "the flash did not hold what was written to it",
                                Details.of(
                                        "region",
                                        plan.region(),
                                        "address",
                                        String.format(Locale.ROOT, "0x%08x", step.address()),
                                        "length",
                                        step.length()));
                    }
                }
            }
        }
        return new FlashOutcome(plan.region(), plan.programmedBytes(), erases, pages, plan.verifies());
    }

    /** The bytes of the image a step covers, stopping at the image's end. */
    private static byte[] slice(byte[] data, long offset, long length) {
        int from = Math.toIntExact(offset);
        return Arrays.copyOfRange(data, from, Math.toIntExact(Math.min(offset + length, data.length)));
    }

    private static String jedec(int id) {
        return String.format(Locale.ROOT, "0x%06x", id);
    }
}
