package io.github.rkaushikdev.carrierworks.reports;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The four kinds of site the bench compares designs on, and the row names each has gone by.
 *
 * <p>Logic, registers, block memory and the multiplier slices decide whether a design will fit a
 * smaller part. Row names have changed between tool versions more than once, so each kind is matched
 * against a small set of names rather than one.
 */
public enum SiteKind {
    LUTS("luts", "slice luts", "clb luts", "total luts", "slice luts*"),
    REGISTERS("registers", "slice registers", "clb registers", "register as flip flop"),
    BLOCK_RAM("block_ram", "block ram tile", "block ram tile*", "ramb36/fifo"),
    DSP("dsp", "dsps", "dsp48e1 only", "dsp slices");

    private final String value;
    private final List<String> rowNames;

    SiteKind(String value, String... rowNames) {
        this.value = value;
        this.rowNames = List.of(rowNames);
    }

    /** The name the kind is asked for under. */
    public String value() {
        return value;
    }

    /** Whether a report row of this name counts this kind, whatever its case and padding. */
    boolean names(String rowName) {
        return rowNames.contains(rowName.strip().toLowerCase(Locale.ROOT));
    }

    /** The kind asked for under exactly this name. */
    public static SiteKind parse(String text) {
        return Arrays.stream(values())
                .filter(kind -> kind.value.equals(text))
                .findFirst()
                .orElseThrow(() -> new ValidationError("no such utilization kind", "kind", Details.of("given", text)));
    }

    @Override
    public String toString() {
        return value;
    }
}
