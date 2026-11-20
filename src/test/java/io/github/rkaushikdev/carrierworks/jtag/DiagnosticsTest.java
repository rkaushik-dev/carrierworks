package io.github.rkaushikdev.carrierworks.jtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.Frequency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiagnosticsTest {

    /** A simulator that goes open circuit on the scans you name, counting from one. */
    private static final class FlakyTransport implements Transport {

        private final Transport inner;
        private final Set<Integer> badScans;
        private int longPulses;

        FlakyTransport(Transport inner, Set<Integer> badScans) {
            this.inner = inner;
            this.badScans = badScans;
        }

        @Override
        public String name() {
            return "flaky";
        }

        @Override
        public void setClock(Frequency clock) {
            inner.setClock(clock);
        }

        @Override
        public void close() {
            inner.close();
        }

        @Override
        public BitVector pulse(BitVector tms, BitVector tdi) {
            BitVector real = inner.pulse(tms, tdi);
            if (tms.length() < 32) {
                return real;
            }
            longPulses++;
            if (badScans.contains(longPulses)) {
                return BitVector.ones(tms.length());
            }
            return real;
        }
    }

    private static ScanChain chain() {
        return ScanChain.ofDevices("xc7a100t", "xc7k325t");
    }

    private static ScanSession session(Integer... badScans) {
        ScanChain live = chain();
        return new ScanSession(new FlakyTransport(ChainSimulator.forChain(live), Set.of(badScans)), live);
    }

    private static ChainAttempt failed(int index, String error) {
        return new ChainAttempt(index, Optional.empty(), Optional.of(error));
    }

    @Test
    void aGoodChainReadsHealthyEveryTime() {
        ChainDiagnosis diagnosis = Diagnostics.diagnoseChain(session());
        assertThat(diagnosis.verdict()).isEqualTo(ChainDiagnosis.Verdict.HEALTHY);
        assertThat(diagnosis.good()).isEqualTo(3);
        assertThat(diagnosis.bad()).isZero();
        assertThat(diagnosis.stable()).isTrue();
        assertThat(diagnosis.worthARetry()).isFalse();
    }

    @Test
    void aHealthyDiagnosisDescribesItselfPlainly() {
        ChainDiagnosis diagnosis = Diagnostics.diagnoseChain(session(), 2);
        assertThat(diagnosis.describe()).startsWith("healthy: 2 of 2 scans agreed with");
    }

    @Test
    void aChainThatNeverAnswersIsAFailureNotAFlake() {
        ChainDiagnosis diagnosis = Diagnostics.diagnoseChain(session(1, 2, 3));
        assertThat(diagnosis.verdict()).isEqualTo(ChainDiagnosis.Verdict.FAILED);
        assertThat(diagnosis.good()).isZero();
        assertThat(diagnosis.worthARetry()).isFalse();
        assertThat(diagnosis.describe()).contains("open chain");
    }

    @Test
    void aChainThatAnswersSometimesIsIntermittent() {
        ChainDiagnosis diagnosis = Diagnostics.diagnoseChain(session(2));
        assertThat(diagnosis.verdict()).isEqualTo(ChainDiagnosis.Verdict.INTERMITTENT);
        assertThat(diagnosis.good()).isEqualTo(2);
        assertThat(diagnosis.bad()).isEqualTo(1);
        assertThat(diagnosis.worthARetry()).isTrue();
    }

    @Test
    void anIntermittentChainSaysTheScansDisagreed() {
        ChainDiagnosis diagnosis = Diagnostics.diagnoseChain(session(2));
        assertThat(diagnosis.stable()).isFalse();
        assertThat(diagnosis.describe()).contains("did not even agree");
    }

    @Test
    void theNumberOfAttemptsIsRespected() {
        ChainDiagnosis diagnosis = Diagnostics.diagnoseChain(session(), 5);
        assertThat(diagnosis.attempts()).hasSize(5);
        assertThat(diagnosis.attempts().stream().map(ChainAttempt::index)).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void askingForNoAttemptsIsABug() {
        assertThatThrownBy(() -> Diagnostics.diagnoseChain(session(), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMismatchedChainFailsConsistently() {
        ScanChain expected = ScanChain.ofDevices("xc7a100t", "xc7a35t");
        ScanChain live = chain();
        ChainSimulator sim = ChainSimulator.forChain(live);
        ChainDiagnosis diagnosis = Diagnostics.diagnoseChain(new ScanSession(sim, live), expected);
        assertThat(diagnosis.verdict()).isEqualTo(ChainDiagnosis.Verdict.FAILED);
        assertThat(diagnosis.stable()).isTrue();
        assertThat(diagnosis.firstError()).contains("the chain is not the one on file");
    }

    @Test
    void theCommonErrorIsTheOneThatCameUpMost() {
        List<ChainAttempt> attempts = List.of(failed(0, "open chain"), failed(1, "held low"), failed(2, "open chain"));
        ChainDiagnosis diagnosis = new ChainDiagnosis("u1", attempts);
        assertThat(diagnosis.commonError()).contains("open chain");
    }

    @Test
    void aCleanDiagnosisHasNoErrorsToReport() {
        ChainDiagnosis diagnosis = Diagnostics.diagnoseChain(session());
        assertThat(diagnosis.firstError()).isEmpty();
        assertThat(diagnosis.commonError()).isEmpty();
    }

    @Test
    void anEmptyDiagnosisSaysItWasNotTested() {
        assertThat(new ChainDiagnosis("u1", List.of()).verdict()).isEqualTo(ChainDiagnosis.Verdict.NOT_TESTED);
    }
}
