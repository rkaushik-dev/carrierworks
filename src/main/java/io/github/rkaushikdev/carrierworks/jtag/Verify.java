package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.ChainError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.catalog.IdCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Checking a chain against the one the bench thinks is there.
 *
 * <p>The inventory says what a board carries. The scan says what answered. When they disagree the
 * useful output is not a boolean, it is which position disagreed and how, because the three common
 * causes look completely different in that detail. A board that came back from rework with the
 * wrong part fitted gives one mismatched idcode at one position. A ribbon plugged into the wrong
 * header gives a chain of the right length with everything in the wrong order. A cable that has
 * worked loose gives no chain at all.
 *
 * <p>The whole capture is worth a look before any of that. All ones and all zeros are both wiring
 * stories rather than device stories, and saying so saves the next person from reading a list of
 * thirty two missing devices.
 */
public final class Verify {

    private Verify() {}

    /** A wiring level reading of a raw capture, or empty if it looks sane. */
    public static Optional<String> diagnoseCapture(BitVector captured) {
        if (captured.length() == 0) {
            return Optional.of("nothing came back at all");
        }
        if (captured.value().signum() == 0) {
            return Optional.of("tdo stayed low, which is a chain held in reset or a board with no power");
        }
        if (captured.equals(BitVector.ones(captured.length()))) {
            return Optional.of("tdo stayed high, which is an open chain or a cable that is not seated");
        }
        if (captured.length() >= Discover.IDCODE_BITS
                && new IdCode(captured.slice(0, Discover.IDCODE_BITS).toLong()).isFloating()) {
            return Optional.of("the first device did not answer");
        }
        return Optional.empty();
    }

    /**
     * Every way two chains differ, position by position.
     *
     * <p>A device that is on the expected chain but at another position is an order difference;
     * anything else in the wrong place is a device difference. Positions past the end of the shorter
     * chain are covered by the one length difference rather than reported one by one.
     */
    public static List<ChainDifference> compareChains(ScanChain expected, ScanChain found) {
        List<ChainDifference> differences = new ArrayList<>();
        if (expected.size() != found.size()) {
            differences.add(ChainDifference.wrongLength(expected.size(), found.size()));
        }
        List<Optional<String>> expectedDevices =
                expected.taps().stream().map(TapPosition::device).toList();
        for (int index = 0; index < Math.min(expected.size(), found.size()); index++) {
            TapPosition want = expected.at(index);
            TapPosition have = found.at(index);
            if (want.device().equals(have.device())) {
                continue;
            }
            ChainDifference.Kind kind =
                    expectedDevices.contains(have.device()) ? ChainDifference.Kind.ORDER : ChainDifference.Kind.DEVICE;
            differences.add(new ChainDifference(
                    kind,
                    Optional.of(index),
                    labelFor(want.device(), want.expectedIdcode()),
                    labelFor(have.device(), have.expectedIdcode())));
        }
        return List.copyOf(differences);
    }

    /**
     * Read a capture, check it against the expected chain, and hand back the chain that answered.
     *
     * <p>Raises rather than returning a verdict, because every caller of this either goes on to
     * program the board or stops, and there is nothing sensible in between. A wiring fault is
     * reported before any device is looked at.
     */
    public static ScanChain verifyChain(ScanChain expected, BitVector captured) {
        Optional<String> wiring = diagnoseCapture(captured);
        if (wiring.isPresent()) {
            throw new ChainError(wiring.get(), Details.of("expected", expected.describe()));
        }
        ScanChain found = Discover.chainFromScan(captured);
        List<ChainDifference> differences = compareChains(expected, found);
        if (!differences.isEmpty()) {
            throw new ChainError(
                    "the chain is not the one on file",
                    Details.of(
                            "expected",
                            expected.describe(),
                            "found",
                            found.describe(),
                            "differences",
                            differences.stream().map(ChainDifference::describe).toList()));
        }
        return found;
    }

    private static String labelFor(Optional<String> device, Optional<Long> idcode) {
        return device.filter(name -> !name.isEmpty())
                .or(() -> idcode.map(code -> new IdCode(code).toString()))
                .orElse("nothing");
    }
}
