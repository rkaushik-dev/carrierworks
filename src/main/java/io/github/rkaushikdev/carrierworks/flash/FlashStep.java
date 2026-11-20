package io.github.rkaushikdev.carrierworks.flash;

import io.github.rkaushikdev.carrierworks.Measure;
import java.util.Locale;
import java.util.Objects;

/**
 * One thing to do to a flash part.
 *
 * @param kind erase, program or verify
 * @param address the first byte it touches
 * @param length how many bytes it touches
 * @param estimatedMs the datasheet typical time it takes
 */
public record FlashStep(StepKind kind, long address, long length, long estimatedMs) {

    public FlashStep {
        Objects.requireNonNull(kind, "kind");
    }

    /** {@code program 0x00800000 256 B ~1ms}. */
    public String describe() {
        return String.format(Locale.ROOT, "%s 0x%08x %s ~%dms", kind, address, Measure.formatSize(length), estimatedMs);
    }
}
