package io.github.rkaushikdev.carrierworks.jtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.ChainError;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VerifyTest {

    private static ScanChain expectedChain() {
        return ScanChain.ofDevices("xc7a100t", "xc7k325t");
    }

    private static List<ChainDifference.Kind> kinds(List<ChainDifference> differences) {
        return differences.stream().map(ChainDifference::kind).toList();
    }

    @Test
    void aHealthyCaptureDiagnosesNothing() {
        assertThat(Verify.diagnoseCapture(Discover.scanBitsFor(expectedChain())))
                .isEmpty();
    }

    @Test
    void anOpenChainIsNamedAsOne() {
        Optional<String> reading = Verify.diagnoseCapture(BitVector.ones(64));
        assertThat(reading).isPresent();
        assertThat(reading.get()).contains("open chain");
    }

    @Test
    void aChainHeldLowIsNamedAsThat() {
        Optional<String> reading = Verify.diagnoseCapture(BitVector.zeros(64));
        assertThat(reading).isPresent();
        assertThat(reading.get()).contains("no power");
    }

    @Test
    void anEmptyCaptureSaysSo() {
        assertThat(Verify.diagnoseCapture(new BitVector(0, 0))).contains("nothing came back at all");
    }

    @Test
    void aFirstDeviceThatDidNotAnswerIsCalledOut() {
        BitVector capture =
                BitVector.ones(32).concat(new BitVector(0x03631093, 32)).concat(BitVector.ones(32));
        Optional<String> reading = Verify.diagnoseCapture(capture);
        assertThat(reading).isPresent();
        assertThat(reading.get()).contains("first device");
    }

    @Test
    void aMatchingChainVerifiesAndComesBack() {
        ScanChain chain = expectedChain();
        ScanChain found = Verify.verifyChain(chain, Discover.scanBitsFor(chain));
        assertThat(found.labels()).isEqualTo(chain.labels());
    }

    @Test
    void twoIdenticalChainsDifferInNoWay() {
        assertThat(Verify.compareChains(expectedChain(), expectedChain())).isEmpty();
    }

    @Test
    void aMissingDeviceShowsUpAsALengthDifference() {
        ScanChain shorter = ScanChain.ofDevices("xc7a100t");
        List<ChainDifference> differences = Verify.compareChains(expectedChain(), shorter);
        assertThat(kinds(differences)).contains(ChainDifference.Kind.LENGTH);
        assertThat(differences.get(0).describe()).isEqualTo("the chain has 1 devices, expected 2");
    }

    @Test
    void aWrongPartAtAPositionIsReportedWithThePosition() {
        ScanChain wrong = ScanChain.ofDevices("xc7a100t", "xc7a35t");
        List<ChainDifference> differences = Verify.compareChains(expectedChain(), wrong);
        assertThat(differences).hasSize(1);
        assertThat(differences.get(0).kind()).isEqualTo(ChainDifference.Kind.DEVICE);
        assertThat(differences.get(0).position()).contains(1);
        assertThat(differences.get(0).describe()).isEqualTo("position 2 holds xc7a35t, expected xc7k325t");
    }

    @Test
    void aSwappedChainIsCalledOutAsOrderRatherThanAsTwoWrongParts() {
        ScanChain swapped = ScanChain.ofDevices("xc7k325t", "xc7a100t");
        List<ChainDifference> differences = Verify.compareChains(expectedChain(), swapped);
        assertThat(kinds(differences)).containsExactly(ChainDifference.Kind.ORDER, ChainDifference.Kind.ORDER);
        assertThat(differences.get(0).describe()).contains("belongs elsewhere");
    }

    @Test
    void verificationRefusesAChainThatDoesNotMatch() {
        ScanChain wrong = ScanChain.ofDevices("xc7a100t", "xc7a35t");
        ChainError caught = catchThrowableOfType(
                ChainError.class, () -> Verify.verifyChain(expectedChain(), Discover.scanBitsFor(wrong)));
        assertThat(caught.details()).containsEntry("found", wrong.describe());
        assertThat(caught.details())
                .containsEntry("differences", List.of("position 2 holds xc7a35t, expected xc7k325t"));
    }

    @Test
    void verificationReportsWiringBeforeItReportsDevices() {
        ChainError caught =
                catchThrowableOfType(ChainError.class, () -> Verify.verifyChain(expectedChain(), BitVector.ones(96)));
        assertThat(caught.message()).contains("open chain");
        assertThat(caught.details()).containsEntry("expected", expectedChain().describe());
    }

    @Test
    void aChainErrorIsRetryableBecauseItIsUsuallyTheCable() {
        ChainError caught =
                catchThrowableOfType(ChainError.class, () -> Verify.verifyChain(expectedChain(), BitVector.zeros(96)));
        assertThat(caught.retryable()).isTrue();
    }

    @Test
    void anExtraDeviceOnTheEndIsALengthAndNothingElse() {
        ScanChain longer = ScanChain.ofDevices("xc7a100t", "xc7k325t", "xc7s25");
        List<ChainDifference> differences = Verify.compareChains(expectedChain(), longer);
        assertThat(kinds(differences)).containsExactly(ChainDifference.Kind.LENGTH);
    }

    @Test
    void aThreeDeviceChainVerifiesEndToEnd() {
        ScanChain chain = ScanChain.ofDevices("xc7s25", "xc7a35t", "xc7k70t");
        assertThat(Verify.verifyChain(chain, Discover.scanBitsFor(chain)).describe())
                .isEqualTo(chain.describe());
    }
}
