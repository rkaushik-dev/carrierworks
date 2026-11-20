package io.github.rkaushikdev.carrierworks.jtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.ChainError;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiscoverTest {

    private static final long ARTIX = 0x03631093L;
    private static final long KINTEX = 0x03651093L;
    private static final long SPARTAN = 0x037C4093L;

    /** A capture of these idcodes, nearest TDO first, and the ones that close the scan. */
    private static BitVector capture(long... words) {
        return capture(Set.of(), words);
    }

    /** The same, with a bypass bit in front of each word whose index is named. */
    private static BitVector capture(Set<Integer> bypassAfter, long... words) {
        BitVector vector = new BitVector(0, 0);
        for (int index = 0; index < words.length; index++) {
            if (bypassAfter.contains(index)) {
                vector = vector.concat(BitVector.zeros(1));
            }
            vector = vector.concat(new BitVector(words[index], 32));
        }
        return vector.concat(BitVector.ones(32));
    }

    private static List<Optional<String>> devices(List<DiscoveredTap> found) {
        return found.stream().map(DiscoveredTap::device).toList();
    }

    @Test
    void oneDeviceReadsBackAsOneDevice() {
        List<DiscoveredTap> found = Discover.discoverTaps(capture(ARTIX));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).device()).contains("xc7a100t");
        assertThat(found.get(0).recognised()).isTrue();
        assertThat(found.get(0).inBypass()).isFalse();
    }

    @Test
    void theFirstIdcodeOutIsTheDeviceNearestTdo() {
        List<DiscoveredTap> found = Discover.discoverTaps(capture(KINTEX, ARTIX));
        assertThat(devices(found)).containsExactly(Optional.of("xc7k325t"), Optional.of("xc7a100t"));
    }

    @Test
    void aBypassBitIsADeviceWithNothingToSay() {
        List<DiscoveredTap> found = Discover.discoverTaps(capture(Set.of(0), ARTIX));
        assertThat(found).hasSize(2);
        assertThat(found.get(0).inBypass()).isTrue();
        assertThat(found.get(0).describe()).isEqualTo("bypass");
        assertThat(found.get(1).device()).contains("xc7a100t");
    }

    @Test
    void anUnknownIdcodeIsReportedRatherThanDropped() {
        List<DiscoveredTap> found = Discover.discoverTaps(capture(0x01234093L));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).idcode()).isPresent();
        assertThat(found.get(0).device()).isEmpty();
        assertThat(found.get(0).recognised()).isFalse();
        assertThat(found.get(0).describe()).isEqualTo("unknown 0x01234093");
    }

    @Test
    void anEmptyChainReadsAsAllOnesAndIsRefused() {
        ChainError caught = catchThrowableOfType(ChainError.class, () -> Discover.discoverTaps(BitVector.ones(32)));
        assertThat(caught.getMessage()).contains("nothing answered");
    }

    @Test
    void aChainThatStopsMidIdcodeIsRefused() {
        BitVector truncated = new BitVector(ARTIX, 32).slice(0, 20);
        assertThatThrownBy(() -> Discover.discoverTaps(truncated)).isInstanceOf(ChainError.class);
    }

    @Test
    void somethingThatIsNotAnIdcodeAtAllIsRefused() {
        assertThatThrownBy(() -> Discover.discoverTaps(new BitVector(0x0000FFFF, 32).concat(BitVector.ones(32))))
                .isInstanceOf(ChainError.class);
    }

    @Test
    void aChainLongerThanTheBenchBelievesInIsRefused() {
        BitVector vector = new BitVector(0, 0);
        for (int device = 0; device < 40; device++) {
            vector = vector.concat(BitVector.zeros(1));
        }
        BitVector scan = vector.concat(BitVector.ones(32));
        assertThatThrownBy(() -> Discover.discoverTaps(scan)).isInstanceOf(ChainError.class);
    }

    @Test
    void aScanOfAKnownChainRoundTrips() {
        ScanChain chain = ScanChain.ofDevices("xc7a100t", "xc7k325t");
        ScanChain rebuilt = Discover.chainFromScan(Discover.scanBitsFor(chain));
        assertThat(rebuilt.labels()).isEqualTo(chain.labels());
        assertThat(rebuilt.taps().stream().map(TapPosition::device))
                .containsExactly(Optional.of("xc7a100t"), Optional.of("xc7k325t"));
        assertThat(rebuilt.irLength()).isEqualTo(chain.irLength());
    }

    @Test
    void theRebuiltChainIsInTdiOrder() {
        ScanChain chain = ScanChain.ofDevices("xc7s25", "xc7a100t", "xc7k325t");
        ScanChain rebuilt = Discover.chainFromScan(Discover.scanBitsFor(chain));
        assertThat(rebuilt.taps().stream().map(TapPosition::device))
                .containsExactly(Optional.of("xc7s25"), Optional.of("xc7a100t"), Optional.of("xc7k325t"));
    }

    @Test
    void theSteppingNibbleDoesNotStopADeviceBeingRecognised() {
        BitVector stepped = capture(ARTIX | 0x40000000L);
        List<DiscoveredTap> found = Discover.discoverTaps(stepped);
        assertThat(found.get(0).device()).contains("xc7a100t");
    }

    @Test
    void aRebuiltPositionStoresTheIdcodeWithoutItsStepping() {
        ScanChain rebuilt = Discover.chainFromScan(capture(ARTIX | 0x40000000L));
        assertThat(rebuilt.taps().get(0).expectedIdcode()).contains(ARTIX);
    }

    @Test
    void aChainWithAnUnknownPartCannotBeRebuilt() {
        ChainError caught = catchThrowableOfType(ChainError.class, () -> Discover.chainFromScan(capture(0x01234093L)));
        assertThat(caught.getMessage()).contains("catalog");
    }

    @Test
    void aChainWithABypassedDeviceCannotBeRebuiltEither() {
        assertThatThrownBy(() -> Discover.chainFromScan(capture(Set.of(0), ARTIX)))
                .isInstanceOf(ChainError.class);
    }

    @Test
    void scanBitsPadABypassedPositionWithASingleZero() {
        ScanChain chain = new ScanChain(List.of(TapPosition.forDevice("u1", "xc7a100t"), new TapPosition("u2", 4)));
        BitVector bits = Discover.scanBitsFor(chain);
        assertThat(bits.length()).isEqualTo(1 + 32 + 32);
        assertThat(bits.bit(0)).isZero();
    }

    @Test
    void scanBitsEndInTheOnesThatWereShiftedIn() {
        BitVector bits = Discover.scanBitsFor(ScanChain.ofDevices("xc7s25"));
        assertThat(bits.slice(32, 32)).isEqualTo(BitVector.ones(32));
        assertThat(Discover.discoverTaps(bits).get(0).device()).contains("xc7s25");
        assertThat(new BitVector(SPARTAN, 32)).isEqualTo(bits.slice(0, 32));
    }
}
