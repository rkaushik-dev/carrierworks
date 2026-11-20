package io.github.rkaushikdev.carrierworks.jtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.ChainError;
import io.github.rkaushikdev.carrierworks.Frequency;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.IdCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionTest {

    private static final long ARTIX = 0x03631093L;
    private static final long KINTEX = 0x03651093L;

    private static ScanSession session(String... devices) {
        ScanChain chain = ScanChain.ofDevices(devices);
        return new ScanSession(ChainSimulator.forChain(chain), chain);
    }

    private static List<Long> values(List<IdCode> codes) {
        return codes.stream().map(IdCode::value).toList();
    }

    @Test
    void aSessionStartsFromReset() {
        ScanSession live = session("xc7a100t");
        assertThat(live.state()).isEqualTo(TapState.TEST_LOGIC_RESET);
        assertThat(live.clocks()).isEqualTo(5);
    }

    @Test
    void theClockIsHandedToTheTransport() {
        ScanChain chain = ScanChain.ofDevices("xc7a100t");
        ChainSimulator sim = ChainSimulator.forChain(chain);
        new ScanSession(sim, chain, Frequency.megahertz(6));
        assertThat(sim.clock()).isEqualTo(Frequency.megahertz(6));
    }

    @Test
    void idcodesComeBackInTheListedOrder() {
        ScanSession live = session("xc7a100t", "xc7k325t");
        assertThat(values(live.readIdcodes())).containsExactly(ARTIX, KINTEX);
    }

    @Test
    void aSingleDeviceChainReadsItsOneIdcode() {
        assertThat(session("xc7k325t").readIdcodes()).containsExactly(new IdCode(KINTEX));
    }

    @Test
    void verificationPassesAgainstTheChainItWasBuiltFrom() {
        ScanSession live = session("xc7a100t", "xc7k325t");
        assertThat(live.verify().labels()).containsExactly("u1", "u2");
    }

    @Test
    void verificationFailsWhenTheBoardCarriesSomethingElse() {
        ScanChain chain = ScanChain.ofDevices("xc7a100t", "xc7k325t");
        ChainSimulator wrong = ChainSimulator.ofDevices("xc7a100t", "xc7a35t");
        ScanSession live = new ScanSession(wrong, chain);
        ChainError caught = catchThrowableOfType(ChainError.class, live::verify);
        assertThat(caught.details())
                .containsEntry("differences", List.of("position 2 holds xc7a35t, expected xc7k325t"));
    }

    @Test
    void anInstructionLandsOnTheNamedDeviceOnly() {
        ScanChain chain = ScanChain.ofDevices("xc7a100t", "xc7k325t");
        ChainSimulator sim = ChainSimulator.forChain(chain);
        ScanSession live = new ScanSession(sim, chain);
        live.loadInstruction("u1", "usercode");
        assertThat(sim.device("u1").instruction()).isEqualTo(Scan.INSTRUCTIONS.get("usercode"));
        assertThat(sim.device("u2").inBypass()).isTrue();
    }

    @Test
    void loadingAnInstructionOnADeviceThatIsNotThereIsANotFound() {
        ScanSession live = session("xc7a100t");
        assertThatThrownBy(() -> live.loadInstruction("u9", "idcode")).isInstanceOf(NotFound.class);
    }

    @Test
    void aDataShiftReturnsOnlyThatDeviceSBits() {
        ScanChain chain = ScanChain.ofDevices("xc7a100t", "xc7k325t");
        ChainSimulator sim = ChainSimulator.forChain(chain);
        ScanSession live = new ScanSession(sim, chain);
        sim.device("u1").setUsercode(0xC0FFEE01L);
        live.loadInstruction("u1", "usercode");
        BitVector read = live.shiftDr("u1", BitVector.zeros(32));
        assertThat(read.toLong()).isEqualTo(0xC0FFEE01L);
        assertThat(read.length()).isEqualTo(32);
    }

    @Test
    void aDataWriteReachesTheDevice() {
        ScanChain chain = ScanChain.ofDevices("xc7a100t");
        ChainSimulator sim = ChainSimulator.forChain(chain);
        ScanSession live = new ScanSession(sim, chain);
        live.loadInstruction("u1", "user1");
        BitVector payload = new BitVector(0x0BADC0DE, 32);
        live.shiftDr("u1", payload);
        assertThat(sim.device("u1").lastDrWrite()).contains(payload);
    }

    @Test
    void shiftingNothingIsRefused() {
        ScanSession live = session("xc7a100t");
        assertThatThrownBy(() -> live.shiftDr("u1", new BitVector(0, 0))).isInstanceOf(ValidationError.class);
    }

    @Test
    void idlingClocksTheControllerWithoutMovingIt() {
        ScanSession live = session("xc7a100t");
        long before = live.clocks();
        live.runTestIdle(10);
        assertThat(live.state()).isEqualTo(TapState.RUN_TEST_IDLE);
        assertThat(live.clocks()).isGreaterThanOrEqualTo(before + 10);
    }

    @Test
    void idlingForANegativeNumberOfClocksIsRefused() {
        ScanSession live = session("xc7a100t");
        assertThatThrownBy(() -> live.runTestIdle(-1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void movingToAStateLeavesTheSessionThere() {
        ScanSession live = session("xc7a100t");
        live.moveTo(TapState.SHIFT_DR);
        assertThat(live.state()).isEqualTo(TapState.SHIFT_DR);
        live.moveTo(TapState.RUN_TEST_IDLE);
        assertThat(live.state()).isEqualTo(TapState.RUN_TEST_IDLE);
    }

    @Test
    void everyClockIsCounted() {
        ScanChain chain = ScanChain.ofDevices("xc7a100t");
        RecordingTransport recorder = new RecordingTransport(ChainSimulator.forChain(chain));
        ScanSession live = new ScanSession(recorder, chain);
        live.readIdcodes();
        assertThat(live.clocks()).isEqualTo(recorder.clocks());
    }

    @Test
    void aSessionClosesItsTransport() {
        ScanChain chain = ScanChain.ofDevices("xc7a100t");
        ChainSimulator sim = ChainSimulator.forChain(chain);
        try (ScanSession live = new ScanSession(sim, chain)) {
            live.readIdcodes();
        }
        assertThat(sim.closed()).isTrue();
    }

    @Test
    void aBypassedDeviceDuringAReadIsReported() {
        ScanChain chain = ScanChain.ofDevices("xc7a100t", "xc7k325t");
        ChainSimulator sim = ChainSimulator.forChain(chain);
        ScanSession live = new ScanSession(sim, chain);
        live.shiftIr(Map.of("u1", "bypass", "u2", "bypass"));
        assertThatThrownBy(() -> live.readIdcodes(false)).isInstanceOf(ChainError.class);
    }

    @Test
    void readingIdcodesResetsFirstSoALeftOverInstructionDoesNotMatter() {
        ScanChain chain = ScanChain.ofDevices("xc7a100t", "xc7k325t");
        ChainSimulator sim = ChainSimulator.forChain(chain);
        ScanSession live = new ScanSession(sim, chain);
        live.shiftIr(Map.of("u1", "bypass", "u2", "bypass"));
        assertThat(values(live.readIdcodes())).containsExactly(ARTIX, KINTEX);
    }
}
