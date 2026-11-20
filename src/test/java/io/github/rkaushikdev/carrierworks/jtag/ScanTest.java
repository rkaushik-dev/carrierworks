package io.github.rkaushikdev.carrierworks.jtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScanTest {

    private static ScanChain chain() {
        return ScanChain.ofDevices("xc7a100t", "xc7k325t");
    }

    private static ScanChain mixed() {
        return new ScanChain(List.of(TapPosition.forDevice("u1", "xc7a100t"), new TapPosition("u2", 4)));
    }

    @Test
    void bypassIsAllOnesAtAnyWidth() {
        assertThat(Scan.bypassInstruction(6)).hasToString("111111");
        assertThat(Scan.bypassInstruction(4)).hasToString("1111");
    }

    @Test
    void namedInstructionsComeOutAtTheDeviceWidth() {
        assertThat(Scan.instructionCode("idcode", 6)).hasToString("001001");
        assertThat(Scan.instructionCode("cfg_in", 6)).hasToString("000101");
        assertThat(Scan.instructionCode("jprogram", 6)).hasToString("001011");
    }

    @Test
    void bypassWidensToWhateverTheDeviceNeeds() {
        assertThat(Scan.instructionCode("bypass", 8)).hasToString("11111111");
    }

    @Test
    void anInstructionThatDoesNotFitIsRefused() {
        assertThatThrownBy(() -> Scan.instructionCode("user3", 4)).isInstanceOf(ValidationError.class);
    }

    @Test
    void anInstructionNobodyHasHeardOfIsANotFound() {
        assertThatThrownBy(() -> Scan.instructionCode("selftest", 6)).isInstanceOf(NotFound.class);
    }

    @Test
    void anAllBypassScanIsAllOnes() {
        assertThat(Scan.irScan(chain())).hasToString("111111111111");
    }

    @Test
    void theNamedDeviceGetsItsInstructionAndTheRestBypass() {
        BitVector scan = Scan.irScan(chain(), Map.of("u1", "idcode"));
        // u2 sits nearest tdo, so its bypass bits shift out first and print last.
        assertThat(scan).hasToString("001001111111");
    }

    @Test
    void namingTheDeviceNearestTdoPutsItsBitsAtTheShiftingEnd() {
        BitVector scan = Scan.irScan(chain(), Map.of("u2", "idcode"));
        assertThat(scan).hasToString("111111001001");
        assertThat(scan.slice(0, 6)).isEqualTo(new BitVector(0b001001, 6));
    }

    @Test
    void bothDevicesCanBeNamedAtOnce() {
        BitVector scan = Scan.irScan(chain(), Map.of("u1", "cfg_in", "u2", "jstart"));
        assertThat(scan).hasToString("000101001100");
    }

    @Test
    void widthsFollowEachDeviceOnAMixedChain() {
        BitVector scan = Scan.irScan(mixed(), Map.of("u1", "idcode"));
        assertThat(scan.length()).isEqualTo(10);
        assertThat(scan).hasToString("0010011111");
    }

    @Test
    void namingADeviceThatIsNotOnTheChainIsANotFound() {
        assertThatThrownBy(() -> Scan.irScan(chain(), Map.of("u9", "idcode"))).isInstanceOf(NotFound.class);
    }

    @Test
    void aDataScanPadsOneBitForEveryOtherDevice() {
        BitVector payload = BitVector.parse("1011");
        BitVector scan = Scan.drScan(chain(), "u1", payload);
        assertThat(scan.length()).isEqualTo(5);
        assertThat(scan).hasToString("10110");
    }

    @Test
    void paddingLandsOnTheCorrectSide() {
        BitVector payload = BitVector.parse("1011");
        BitVector scan = Scan.drScan(chain(), "u2", payload);
        assertThat(scan).hasToString("01011");
        assertThat(scan.bit(0)).isEqualTo(1);
        assertThat(scan.bit(4)).isZero();
    }

    @Test
    void theCaptureOffsetCountsDevicesNearerTdo() {
        assertThat(Scan.drCaptureOffset(chain(), "u1")).isEqualTo(1);
        assertThat(Scan.drCaptureOffset(chain(), "u2")).isZero();
    }

    @Test
    void aPayloadIsPulledBackOutOfAWholeCapture() {
        BitVector idcode = new BitVector(0x03631093, 32);
        BitVector captured = BitVector.zeros(1).concat(idcode);
        BitVector payload = Scan.takeDrPayload(chain(), "u1", captured, 32);
        assertThat(payload.length()).isEqualTo(32);
        assertThat(payload).isEqualTo(idcode);
    }

    @Test
    void aCaptureOfTheWrongLengthIsRefused() {
        assertThatThrownBy(() -> Scan.takeDrPayload(chain(), "u1", BitVector.zeros(32), 32))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aScanAndItsExtractionAgreeOnAThreeDeviceChain() {
        ScanChain three = ScanChain.ofDevices("xc7a35t", "xc7a100t", "xc7s25");
        BitVector payload = BitVector.parse("110000111100001111000011110000");
        BitVector scan = Scan.drScan(three, "u2", payload);
        assertThat(scan.length()).isEqualTo(payload.length() + 2);
        assertThat(Scan.takeDrPayload(three, "u2", scan, payload.length())).isEqualTo(payload);
    }
}
