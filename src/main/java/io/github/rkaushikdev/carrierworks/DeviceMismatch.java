package io.github.rkaushikdev.carrierworks;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An image and a device were paired that do not belong together.
 *
 * <p>Kept separate from {@link ValidationError} because this one is the single most expensive
 * mistake the bench can make. Loading a Kintex image into an Artix will not brick the part, but it
 * will burn an hour of somebody's afternoon before anyone thinks to check the idcode.
 */
public class DeviceMismatch extends BenchError {

    private final String expected;
    private final String found;

    public DeviceMismatch(String message, String expected, String found) {
        this(message, expected, found, Map.of());
    }

    public DeviceMismatch(String message, String expected, String found, Map<String, ?> details) {
        super(message, sides(expected, found, details));
        this.expected = expected;
        this.found = found;
    }

    private static Map<String, Object> sides(String expected, String found, Map<String, ?> details) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("expected", expected);
        merged.put("found", found);
        merged.putAll(details);
        return merged;
    }

    /** What the image was built for. */
    public String expected() {
        return expected;
    }

    /** What it was about to be loaded into. */
    public String found() {
        return found;
    }

    @Override
    public String code() {
        return "device_mismatch";
    }
}
