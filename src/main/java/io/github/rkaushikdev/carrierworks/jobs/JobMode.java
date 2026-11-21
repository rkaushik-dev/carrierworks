package io.github.rkaushikdev.carrierworks.jobs;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Arrays;

/**
 * How a programming job gets its image onto the board.
 *
 * <p>Three modes, and they are genuinely different operations rather than a flag on one. Loading
 * over JTAG puts an image straight into the device and it is gone at the next power cycle, which is
 * what a regression wants. Writing the update region of the flash changes what the board boots
 * into. Writing the golden region changes what it falls back to when an update is bad, which is the
 * operation nobody should be able to do by accident.
 */
public enum JobMode {
    JTAG("jtag"),
    FLASH_UPDATE("flash-update"),
    FLASH_GOLDEN("flash-golden");

    private final String value;

    JobMode(String value) {
        this.value = value;
    }

    /** The name the mode is stored, printed and asked for under. */
    public String value() {
        return value;
    }

    /** Whether the job changes what is in the configuration flash. */
    public boolean writesFlash() {
        return value.startsWith("flash");
    }

    /** The one mode that can leave a board unable to boot at all. */
    public boolean touchesGolden() {
        return this == FLASH_GOLDEN;
    }

    /** The mode with this name, however it was written. */
    public static JobMode parse(String text) {
        String name = Ids.slug(text);
        return Arrays.stream(values())
                .filter(mode -> mode.value.equals(name))
                .findFirst()
                .orElseThrow(() -> new ValidationError("no such programming mode", "mode", Details.of("given", name)));
    }

    /**
     * The mode with exactly this name, which is how a submission names one.
     *
     * <p>A job submitted from the command line or over HTTP has to say {@code jtag},
     * {@code flash-update} or {@code flash-golden} as written; {@code JTAG} or {@code flash_update}
     * is refused rather than guessed at, because the mode decides which flash region gets written.
     */
    public static JobMode exactly(String text) {
        return Arrays.stream(values())
                .filter(mode -> mode.value.equals(text))
                .findFirst()
                .orElseThrow(() -> new ValidationError("no such programming mode", "mode", Details.of("given", text)));
    }

    @Override
    public String toString() {
        return value;
    }
}
