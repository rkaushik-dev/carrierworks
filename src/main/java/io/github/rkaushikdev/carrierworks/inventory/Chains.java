package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.ChainError;
import io.github.rkaushikdev.carrierworks.catalog.IdCode;
import io.github.rkaushikdev.carrierworks.jtag.BitVector;
import io.github.rkaushikdev.carrierworks.jtag.ChainDifference;
import io.github.rkaushikdev.carrierworks.jtag.Discover;
import io.github.rkaushikdev.carrierworks.jtag.DiscoveredTap;
import io.github.rkaushikdev.carrierworks.jtag.ScanChain;
import io.github.rkaushikdev.carrierworks.jtag.TapPosition;
import io.github.rkaushikdev.carrierworks.jtag.Verify;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The chain a board ought to present, and what to do when it does not.
 *
 * <p>Most carriers put one device on the header and that is the whole chain. Two of the ones in
 * this lab do not: the capture carriers have a small system controller in front of the FPGA, and
 * the older test rig has a pair of them. Those are not devices the bench stocks, because nothing
 * ever loads a bitstream into one, but they are on the chain and every scan has to shift past them.
 *
 * <p>So the extra positions are declared per carrier model, with the idcode they answer with and
 * the width of their instruction register, and that is all the bench needs to know about them.
 * Reconciling a scan against that chain is the other half, and its result is a {@link ChainCheck}.
 */
public final class Chains {

    /**
     * Positions that sit on the header in front of the device, by carrier model, each in TDI order.
     * This table is the only place controllers are named.
     */
    public static final Map<String, List<TapPosition>> CARRIER_TAPS = carrierTaps();

    private static final String DEVICE_LABEL = "u1";

    private Chains() {}

    private static Map<String, List<TapPosition>> carrierTaps() {
        TapPosition sysctl = new TapPosition("sysctl", 8, 0x06E5E093L);
        Map<String, List<TapPosition>> taps = new LinkedHashMap<>();
        taps.put("rx-carrier-b", List.of(sysctl));
        taps.put("rx-carrier-a", List.of(sysctl));
        taps.put("hil-rig-1", List.of(sysctl, new TapPosition("pmc", 8, 0x06E1C093L)));
        return Collections.unmodifiableMap(taps);
    }

    /** The chain this board should present, controllers and all, with its device labelled {@code u1}. */
    public static ScanChain chainFor(Board board) {
        List<TapPosition> positions = new ArrayList<>(CARRIER_TAPS.getOrDefault(board.model(), List.of()));
        positions.add(TapPosition.forDevice(DEVICE_LABEL, board.device().device()));
        return new ScanChain(positions);
    }

    /**
     * Compare what a board answered with against what it should have.
     *
     * <p>A capture that reads as a wiring fault, or that cannot be read as a chain at all, is
     * reported as wiring. Anything else is compared position by position.
     */
    public static ChainCheck reconcile(Board board, BitVector captured) {
        ScanChain expected = chainFor(board);
        Optional<String> wiring = Verify.diagnoseCapture(captured);
        if (wiring.isPresent()) {
            return ChainCheck.wiringFault(board.tag(), expected.describe(), wiring.get());
        }
        List<DiscoveredTap> answered;
        try {
            answered = Discover.discoverTaps(captured);
        } catch (ChainError failure) {
            return ChainCheck.wiringFault(board.tag(), expected.describe(), failure.message());
        }
        List<DiscoveredTap> found = answered.reversed();
        return new ChainCheck(
                board.tag(),
                expected.describe(),
                Optional.empty(),
                differences(expected, found),
                found.stream().map(DiscoveredTap::describe).collect(Collectors.joining(" -> ")));
    }

    /**
     * Compare position by position on idcode, in TDI order.
     *
     * <p>Not the general chain comparison, because half the positions on these boards are
     * controllers the catalog has never heard of. An idcode, stepping ignored, is enough to say
     * whether the right thing is in the right place.
     */
    private static List<ChainDifference> differences(ScanChain expected, List<DiscoveredTap> found) {
        List<TapPosition> wanted = expected.taps();
        List<ChainDifference> differences = new ArrayList<>();
        if (wanted.size() != found.size()) {
            differences.add(ChainDifference.wrongLength(wanted.size(), found.size()));
        }
        List<Optional<Long>> expectedCodes = wanted.stream()
                .map(tap -> tap.expectedIdcode().map(Chains::withoutVersion))
                .toList();
        for (int index = 0; index < Math.min(wanted.size(), found.size()); index++) {
            TapPosition tap = wanted.get(index);
            DiscoveredTap answered = found.get(index);
            Optional<Long> code =
                    answered.idcode().map(idcode -> idcode.withoutVersion().value());
            if (code.equals(expectedCodes.get(index))) {
                continue;
            }
            ChainDifference.Kind kind =
                    expectedCodes.contains(code) ? ChainDifference.Kind.ORDER : ChainDifference.Kind.DEVICE;
            differences.add(new ChainDifference(
                    kind,
                    Optional.of(index),
                    label(tap.expectedIdcode(), tap.device()),
                    label(answered.idcode().map(IdCode::value), answered.device())));
        }
        return List.copyOf(differences);
    }

    private static long withoutVersion(long idcode) {
        return new IdCode(idcode).withoutVersion().value();
    }

    private static String label(Optional<Long> idcode, Optional<String> device) {
        return device.filter(name -> !name.isEmpty())
                .or(() -> idcode.map(code -> new IdCode(code).toString()))
                .orElse("bypass");
    }
}
