package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.ChainError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.catalog.DeviceSpec;
import io.github.rkaushikdev.carrierworks.catalog.Devices;
import io.github.rkaushikdev.carrierworks.catalog.IdCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Working out what is on a chain from the bits that came back.
 *
 * <p>After a reset every device loads its idcode into the data register, except the ones that have
 * no idcode, which sit in bypass instead. So a data register scan with ones going in reads back as
 * a run of thirty two bit idcodes with single zero bits wherever a device had nothing to say. The
 * two cases are told apart by one bit: the lowest bit of an idcode is always one, and the bypass
 * register always captures zero.
 *
 * <p>The end of the chain is a run of ones, because that is what was shifted in and nothing shifted
 * it back out again. Thirty two of them in a row means every device has been accounted for.
 *
 * <p>Nearest TDO comes back first, so the walk here produces the chain in the order the bits
 * arrived, and the caller reverses it to get the order a schematic reads.
 */
public final class Discover {

    /** How wide an idcode is, and how many ones mark the end of a discovery scan. */
    public static final int IDCODE_BITS = 32;

    private static final int MAX_DEVICES = 32;

    private Discover() {}

    /** Read a chain out of a raw capture, nearest TDO first. */
    public static List<DiscoveredTap> discoverTaps(BitVector captured) {
        List<DiscoveredTap> found = new ArrayList<>();
        int offset = 0;
        int total = captured.length();
        while (offset < total) {
            int remaining = total - offset;
            if (captured.bit(offset) == 0) {
                found.add(DiscoveredTap.bypass());
                offset += 1;
            } else {
                if (remaining < IDCODE_BITS) {
                    throw new ChainError(
                            "chain ended part way through an idcode",
                            Details.of("read", captured.slice(offset, remaining).toString()));
                }
                IdCode code = new IdCode(captured.slice(offset, IDCODE_BITS).toLong());
                if (code.isFloating()) {
                    break;
                }
                if (!code.isPlausible()) {
                    throw new ChainError(
                            "chain returned something that is not an idcode", Details.of("idcode", code.toString()));
                }
                Optional<DeviceSpec> spec = Devices.all().stream()
                        .filter(known -> known.matchesIdcode(code.value()))
                        .findFirst();
                found.add(new DiscoveredTap(
                        Optional.of(code), spec.map(DeviceSpec::device), spec.map(DeviceSpec::irLength)));
                offset += IDCODE_BITS;
            }
            if (found.size() > MAX_DEVICES) {
                throw new ChainError("more devices than the bench believes in", Details.of("found", found.size()));
            }
        }
        if (found.isEmpty()) {
            throw new ChainError("nothing answered on the chain");
        }
        return List.copyOf(found);
    }

    /**
     * A usable chain description, in TDI order, from a discovery scan.
     *
     * <p>Every device has to be one the catalog knows, because an unknown part has an unknown
     * instruction register width and nothing can be shifted past it safely. That is a configuration
     * job, not something to guess at. Positions are labelled {@code u1}, {@code u2} and so on, and
     * each keeps the idcode it answered with, stepping cleared.
     */
    public static ScanChain chainFromScan(BitVector captured) {
        List<DiscoveredTap> found = discoverTaps(captured);
        List<String> unknown = found.stream()
                .filter(tap -> tap.irLength().isEmpty())
                .map(DiscoveredTap::describe)
                .toList();
        if (!unknown.isEmpty()) {
            throw new ChainError("chain holds a device the catalog does not know", Details.of("idcodes", unknown));
        }
        List<TapPosition> positions = new ArrayList<>(found.size());
        for (DiscoveredTap tap : found.reversed()) {
            positions.add(new TapPosition(
                    "u" + (positions.size() + 1),
                    tap.irLength().orElseThrow(),
                    tap.device(),
                    tap.idcode().map(code -> code.withoutVersion().value())));
        }
        return new ScanChain(positions);
    }

    /**
     * What a healthy discovery scan of this chain would read back.
     *
     * <p>Used by the simulated adapter and by the tests, and it is the only honest way to check the
     * reader without a board on the bench.
     */
    public static BitVector scanBitsFor(ScanChain chain) {
        List<BitVector> answers = new ArrayList<>();
        for (TapPosition tap : chain.inTdoOrder()) {
            answers.add(tap.expectedIdcode()
                    .map(idcode -> new BitVector(idcode, IDCODE_BITS))
                    .orElseGet(() -> BitVector.zeros(1)));
        }
        answers.add(BitVector.ones(IDCODE_BITS));
        return BitVector.join(answers);
    }
}
