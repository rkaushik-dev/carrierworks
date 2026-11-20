package io.github.rkaushikdev.carrierworks.jtag;

import static io.github.rkaushikdev.carrierworks.jtag.TapState.RUN_TEST_IDLE;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.SHIFT_DR;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.SHIFT_IR;
import static io.github.rkaushikdev.carrierworks.jtag.TapState.TEST_LOGIC_RESET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.Frequency;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SimulatorTest {

    private static final long ARTIX = 0x03631093L;
    private static final long KINTEX = 0x03651093L;

    private static void travel(ChainSimulator sim, TapState target) {
        List<Integer> path = Tap.pathBetween(sim.state(), target);
        if (!path.isEmpty()) {
            sim.pulse(BitVector.fromBits(path), BitVector.zeros(path.size()));
        }
    }

    /** Shift a payload through a shift state and come back to idle. */
    private static BitVector shift(ChainSimulator sim, TapState target, BitVector payload) {
        travel(sim, target);
        List<Integer> tms = new ArrayList<>();
        for (int clock = 1; clock < payload.length(); clock++) {
            tms.add(0);
        }
        tms.add(1);
        BitVector out = sim.pulse(BitVector.fromBits(tms), payload);
        travel(sim, RUN_TEST_IDLE);
        return out;
    }

    @Test
    void aFreshChainIsInResetWithIdcodeLoaded() {
        ChainSimulator sim = ChainSimulator.ofDevices("xc7a100t");
        assertThat(sim.state()).isEqualTo(TEST_LOGIC_RESET);
        assertThat(sim.devices().get(0).instruction()).isEqualTo(Scan.INSTRUCTIONS.get("idcode"));
    }

    @Test
    void aSingleDeviceShiftsOutItsIdcode() {
        ChainSimulator sim = ChainSimulator.ofDevices("xc7a100t");
        BitVector out = shift(sim, SHIFT_DR, BitVector.ones(32));
        assertThat(out.toLong()).isEqualTo(ARTIX);
    }

    @Test
    void theDeviceNearestTdoComesOutFirst() {
        ChainSimulator sim = ChainSimulator.ofDevices("xc7a100t", "xc7k325t");
        BitVector out = shift(sim, SHIFT_DR, BitVector.ones(64));
        assertThat(out.slice(0, 32).toLong()).isEqualTo(KINTEX);
        assertThat(out.slice(32, 32).toLong()).isEqualTo(ARTIX);
    }

    @Test
    void theDiscoveryReaderAgreesWithTheSimulator() {
        ChainSimulator sim = ChainSimulator.ofDevices("xc7a100t", "xc7k325t");
        BitVector out = shift(sim, SHIFT_DR, BitVector.ones(96));
        List<DiscoveredTap> found = Discover.discoverTaps(out);
        assertThat(found.stream().map(DiscoveredTap::device))
                .containsExactly(Optional.of("xc7k325t"), Optional.of("xc7a100t"));
    }

    @Test
    void theInstructionRegisterCapturesTheStandardPattern() {
        ChainSimulator sim = ChainSimulator.ofDevices("xc7a100t");
        BitVector out = shift(sim, SHIFT_IR, BitVector.ones(6));
        assertThat(out).isEqualTo(new BitVector(0b01, 6));
    }

    @Test
    void shiftingBypassIntoTheInstructionRegisterTakesEffect() {
        ChainSimulator sim = ChainSimulator.ofDevices("xc7a100t");
        shift(sim, SHIFT_IR, BitVector.ones(6));
        assertThat(sim.devices().get(0).inBypass()).isTrue();
    }

    @Test
    void aBypassedDeviceContributesASingleBit() {
        ChainSimulator sim = ChainSimulator.ofDevices("xc7a100t", "xc7k325t");
        shift(sim, SHIFT_IR, Scan.irScan(ScanChain.ofDevices("xc7a100t", "xc7k325t")));
        assertThat(sim.devices()).allMatch(SimulatedDevice::inBypass);
        BitVector out = shift(sim, SHIFT_DR, BitVector.ones(2));
        assertThat(out.length()).isEqualTo(2);
        assertThat(out.toLong()).isZero();
    }

    @Test
    void oneDeviceCanStayInIdcodeWhileTheOtherBypasses() {
        ScanChain chain = ScanChain.ofDevices("xc7a100t", "xc7k325t");
        ChainSimulator sim = ChainSimulator.forChain(chain);
        shift(sim, SHIFT_IR, Scan.irScan(chain, Map.of("u1", "idcode")));
        assertThat(sim.devices().get(0).inBypass()).isFalse();
        assertThat(sim.devices().get(1).inBypass()).isTrue();
        BitVector out = shift(sim, SHIFT_DR, BitVector.ones(33));
        // the kintex is nearest tdo and is in bypass, so one zero comes out first.
        assertThat(out.bit(0)).isZero();
        assertThat(out.slice(1, 32).toLong()).isEqualTo(ARTIX);
    }

    @Test
    void aUsercodeInstructionReadsTheUsercode() {
        ChainSimulator sim = ChainSimulator.ofDevices("xc7a100t");
        sim.devices().get(0).setUsercode(0xDEADBEEFL);
        shift(sim, SHIFT_IR, new BitVector(Scan.INSTRUCTIONS.get("usercode"), 6));
        BitVector out = shift(sim, SHIFT_DR, BitVector.zeros(32));
        assertThat(out.toLong()).isEqualTo(0xDEADBEEFL);
    }

    @Test
    void aDataWriteIsKeptForInspection() {
        ChainSimulator sim = ChainSimulator.ofDevices("xc7a100t");
        shift(sim, SHIFT_IR, new BitVector(Scan.INSTRUCTIONS.get("user1"), 6));
        BitVector payload = new BitVector(0x1234ABCD, 32);
        shift(sim, SHIFT_DR, payload);
        assertThat(sim.devices().get(0).lastDrWrite()).contains(payload);
    }

    @Test
    void fiveOnesPutEverythingBackToIdcode() {
        ChainSimulator sim = ChainSimulator.ofDevices("xc7a100t");
        shift(sim, SHIFT_IR, BitVector.ones(6));
        assertThat(sim.devices().get(0).inBypass()).isTrue();
        sim.pulse(BitVector.ones(5), BitVector.zeros(5));
        assertThat(sim.state()).isEqualTo(TEST_LOGIC_RESET);
        assertThat(sim.devices().get(0).instruction()).isEqualTo(Scan.INSTRUCTIONS.get("idcode"));
    }

    @Test
    void theSimulatorRemembersTheClockItWasGiven() {
        ChainSimulator sim = ChainSimulator.ofDevices("xc7a100t");
        sim.setClock(Frequency.megahertz(15));
        assertThat(sim.clock()).isEqualTo(Frequency.megahertz(15));
    }

    @Test
    void aClosedSimulatorRefusesToClock() {
        ChainSimulator sim = ChainSimulator.ofDevices("xc7a100t");
        sim.close();
        assertThatThrownBy(() -> sim.pulse(BitVector.ones(1), BitVector.zeros(1)))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void mismatchedOrEmptyPulsesAreRefused() {
        ChainSimulator sim = ChainSimulator.ofDevices("xc7a100t");
        assertThatThrownBy(() -> sim.pulse(BitVector.ones(2), BitVector.zeros(3)))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> sim.pulse(new BitVector(0, 0), new BitVector(0, 0)))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aChainOfNothingIsRefused() {
        assertThatThrownBy(() -> new ChainSimulator(List.of())).isInstanceOf(ValidationError.class);
    }

    @Test
    void aChainWithAnUnknownDeviceCannotBeSimulated() {
        ScanChain chain = new ScanChain(List.of(new TapPosition("u1", 6)));
        assertThatThrownBy(() -> ChainSimulator.forChain(chain)).isInstanceOf(ValidationError.class);
    }

    @Test
    void devicesAreFoundByLabel() {
        ChainSimulator sim = ChainSimulator.ofDevices("xc7a100t", "xc7k325t");
        assertThat(sim.device("u2").idcode()).isEqualTo(KINTEX);
        assertThatThrownBy(() -> sim.device("u9")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aHandBuiltDeviceCanStandInForACataloguedOne() {
        ChainSimulator sim = new ChainSimulator(List.of(new SimulatedDevice("dap", 0x4BA00477L, 4)));
        BitVector out = shift(sim, SHIFT_DR, BitVector.ones(32));
        assertThat(out.toLong()).isEqualTo(0x4BA00477L);
    }
}
