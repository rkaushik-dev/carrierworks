package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Every device between TDI and TDO, listed in that order.
 *
 * <p>Order is the thing to get right and it is easy to state wrongly. The devices are listed the
 * way the vendor tools list them and the way the schematic reads, from the connector's TDI pin
 * through each part to TDO. Bits that come back arrive from the device nearest TDO first, so a scan
 * of the whole chain reads in the reverse of the listed order. Every method here says which of the
 * two it means in its name, because the one time it was implicit the boundary scan came out
 * mirrored and nobody could see why.
 *
 * @param taps the positions, in TDI order
 */
public record ScanChain(List<TapPosition> taps) {

    public ScanChain {
        taps = List.copyOf(taps);
        if (taps.isEmpty()) {
            throw new ConfigurationError("a chain has at least one device");
        }
        List<String> labels = taps.stream().map(TapPosition::label).toList();
        if (new HashSet<>(labels).size() != labels.size()) {
            throw new ConfigurationError("two positions share a label", Details.of("labels", labels));
        }
    }

    /** A chain of catalogued parts, labelled {@code u1}, {@code u2} and so on in TDI order. */
    public static ScanChain ofDevices(String... devices) {
        List<TapPosition> taps = new ArrayList<>(devices.length);
        for (int index = 0; index < devices.length; index++) {
            taps.add(TapPosition.forDevice("u" + (index + 1), devices[index]));
        }
        return new ScanChain(taps);
    }

    /** How many positions there are. */
    public int size() {
        return taps.size();
    }

    /** Total instruction register width of the whole chain. */
    public int irLength() {
        return taps.stream().mapToInt(TapPosition::irLength).sum();
    }

    /** Where the position with this label sits in TDI order, or a not found. */
    public int indexOf(String label) {
        String wanted = Ids.slug(label);
        for (int index = 0; index < taps.size(); index++) {
            if (taps.get(index).label().equals(wanted)) {
                return index;
            }
        }
        throw new NotFound("tap", wanted);
    }

    /** The position at an index in TDI order. */
    public TapPosition at(int index) {
        if (index < 0 || index >= taps.size()) {
            throw new ValidationError("no position at that index", "index", Details.of("given", index));
        }
        return taps.get(index);
    }

    /** The position with this label, or a not found. */
    public TapPosition tap(String label) {
        return taps.get(indexOf(label));
    }

    /**
     * How many devices sit between this one and TDO.
     *
     * <p>Their bits come back first, so this is the number of bypass bits to drop off the front of a
     * data register scan.
     */
    public int devicesBeforeTdo(int index) {
        at(index);
        return taps.size() - index - 1;
    }

    /** How many sit on the TDI side, whose bits arrive after this one's. */
    public int devicesAfterTdo(int index) {
        at(index);
        return index;
    }

    /** Instruction bits belonging to devices nearer TDO than this one. */
    public int irBitsBefore(int index) {
        at(index);
        return taps.subList(index + 1, taps.size()).stream()
                .mapToInt(TapPosition::irLength)
                .sum();
    }

    /** Instruction bits belonging to devices on the TDI side of this one. */
    public int irBitsAfter(int index) {
        at(index);
        return taps.subList(0, index).stream().mapToInt(TapPosition::irLength).sum();
    }

    /** The chain as the bits come back, nearest TDO first. */
    public List<TapPosition> inTdoOrder() {
        return taps.reversed();
    }

    /** Every label, in TDI order. */
    public List<String> labels() {
        return taps.stream().map(TapPosition::label).toList();
    }

    /** One line for a log, in TDI order with widths: {@code u1:xc7a100t/ir6 -> u2:unknown/ir4}. */
    public String describe() {
        return taps.stream()
                .map(tap -> tap.label() + ":" + tap.device().orElse("unknown") + "/ir" + tap.irLength())
                .collect(Collectors.joining(" -> "));
    }
}
