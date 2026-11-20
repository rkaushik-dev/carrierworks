package io.github.rkaushikdev.carrierworks.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.carrierworks.BenchError;
import io.github.rkaushikdev.carrierworks.bitstream.Binary;
import io.github.rkaushikdev.carrierworks.jtag.ChainSimulator;
import io.github.rkaushikdev.carrierworks.jtag.ScanChain;
import io.github.rkaushikdev.carrierworks.jtag.ScanSession;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProgrammerTest {

    /** Every byte value in order, four times over. */
    private static final byte[] IMAGE = counting(4);

    private static final byte[] SHORT = {0x01, 0x02, 0x03, 0x04};

    private static byte[] counting(int repeats) {
        byte[] data = new byte[256 * repeats];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) index;
        }
        return data;
    }

    /** A session over a simulated chain, and the simulator to look inside afterwards. */
    private record Rig(ScanSession live, ChainSimulator sim) {}

    private static Rig session() {
        return session("xc7a100t");
    }

    private static Rig session(String... devices) {
        ScanChain chain = ScanChain.ofDevices(devices);
        ChainSimulator sim = ChainSimulator.forChain(chain);
        return new Rig(new ScanSession(sim, chain), sim);
    }

    @Test
    void aLoadLeavesTheDeviceConfigured() {
        Rig rig = session();
        ProgramOutcome outcome =
                Programmer.loadOverJtag(rig.live(), IMAGE, JtagLoad.defaults().withBoard("bx-14"));
        assertThat(outcome.done()).isTrue();
        assertThat(rig.sim().device("u1").configured()).isTrue();
        assertThat(outcome.bytesSent()).isEqualTo(IMAGE.length);
    }

    @Test
    void theImageArrivesBitReversedTheWayThePortWantsIt() {
        Rig rig = session();
        Programmer.loadOverJtag(rig.live(), IMAGE);
        assertThat(rig.sim().device("u1").configData()).containsExactly(Binary.reverseBits(IMAGE));
    }

    @Test
    void theSwapCanBeTurnedOff() {
        Rig rig = session();
        Programmer.loadOverJtag(rig.live(), IMAGE, JtagLoad.defaults().withSwap(false));
        assertThat(rig.sim().device("u1").configData()).containsExactly(IMAGE);
    }

    @Test
    void aLoadCountsItsClocks() {
        Rig rig = session();
        ProgramOutcome outcome = Programmer.loadOverJtag(rig.live(), IMAGE);
        assertThat(outcome.clocks()).isGreaterThan(IMAGE.length * 8L);
        // the session reset itself before the load, and those five clocks are not the load's
        assertThat(outcome.clocks()).isEqualTo(rig.live().clocks() - 5);
    }

    @Test
    void theOutcomeDescribesItself() {
        Rig rig = session();
        ProgramOutcome outcome =
                Programmer.loadOverJtag(rig.live(), SHORT, JtagLoad.defaults().withBoard("bx-14"));
        assertThat(outcome.describe()).startsWith("bx-14 u1: 4 bytes,");
        assertThat(outcome.describe()).endsWith("done");
    }

    @Test
    void loadingNothingIsABug() {
        Rig rig = session();
        assertThatThrownBy(() -> Programmer.loadOverJtag(rig.live(), new byte[0]))
                .isInstanceOf(BenchError.class);
    }

    @Test
    void aDeviceThatDoesNotComeUpIsARetryableFailure() {
        ScanChain chain = ScanChain.ofDevices("xc7a100t");
        DeafDevice deaf = new DeafDevice("u1", 0x03631093L, 6);
        ScanSession live = new ScanSession(new ChainSimulator(List.of(deaf)), chain);
        ProgrammingFailed caught = catchThrowableOfType(
                ProgrammingFailed.class,
                () -> Programmer.loadOverJtag(live, IMAGE, JtagLoad.defaults().withBoard("bx-14")));
        assertThat(caught.retryable()).isTrue();
        assertThat(caught.details()).containsEntry("board", "bx-14");
        assertThat(caught.details()).containsEntry("status", "0x00000000");
    }

    @Test
    void theSecondDeviceOnAChainCanBeProgrammed() {
        Rig rig = session("xc7a100t", "xc7k325t");
        Programmer.loadOverJtag(rig.live(), IMAGE, JtagLoad.defaults().withLabel("u2"));
        assertThat(rig.sim().device("u2").configured()).isTrue();
        assertThat(rig.sim().device("u1").configured()).isFalse();
    }

    @Test
    void programmingADeviceThatIsNotOnTheChainIsRefused() {
        Rig rig = session();
        assertThatThrownBy(() -> Programmer.loadOverJtag(
                        rig.live(), IMAGE, JtagLoad.defaults().withLabel("u9")))
                .isInstanceOf(BenchError.class);
    }

    @Test
    void aSecondLoadReplacesTheFirst() {
        Rig rig = session();
        Programmer.loadOverJtag(rig.live(), IMAGE);
        Programmer.loadOverJtag(rig.live(), SHORT);
        assertThat(rig.sim().device("u1").configData()).containsExactly(Binary.reverseBits(SHORT));
        assertThat(rig.sim().device("u1").configured()).isTrue();
    }

    @Test
    void aChainScanAfterALoadDoesNotWipeTheDevice() {
        Rig rig = session();
        Programmer.loadOverJtag(rig.live(), IMAGE);
        rig.live().readIdcodes();
        assertThat(rig.sim().device("u1").configured()).isTrue();
    }

    @Test
    void theStatusWordComesBackWithTheOutcome() {
        Rig rig = session();
        ProgramOutcome outcome = Programmer.loadOverJtag(rig.live(), IMAGE);
        assertThat(outcome.status() & (1L << 14)).isNotZero();
    }
}
