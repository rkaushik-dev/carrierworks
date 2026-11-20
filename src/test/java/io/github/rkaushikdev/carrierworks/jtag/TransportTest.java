package io.github.rkaushikdev.carrierworks.jtag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.carrierworks.Frequency;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransportTest {

    private static BitVector bits(String text) {
        return BitVector.parse(text);
    }

    @Test
    void theLoopbackReturnsWhatWentIn() {
        LoopbackTransport transport = new LoopbackTransport();
        BitVector tdi = bits("1011");
        assertThat(transport.pulse(BitVector.zeros(4), tdi)).isEqualTo(tdi);
    }

    @Test
    void aTransportTakesAClock() {
        LoopbackTransport transport = new LoopbackTransport();
        transport.setClock(Frequency.megahertz(6));
        assertThat(transport.clock()).isEqualTo(Frequency.megahertz(6));
    }

    @Test
    void mismatchedLengthsAreRefused() {
        assertThatThrownBy(() -> new LoopbackTransport().pulse(BitVector.zeros(4), BitVector.zeros(5)))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aPulseOfNothingIsRefused() {
        assertThatThrownBy(() -> new LoopbackTransport().pulse(new BitVector(0, 0), new BitVector(0, 0)))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aClosedTransportRefusesToClock() {
        LoopbackTransport transport = new LoopbackTransport();
        transport.close();
        assertThat(transport.closed()).isTrue();
        assertThatThrownBy(() -> transport.pulse(BitVector.zeros(2), BitVector.zeros(2)))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void bothTransportsSatisfyTheProtocol() {
        assertThat(new LoopbackTransport()).isInstanceOf(Transport.class);
        assertThat(new RecordingTransport(new LoopbackTransport())).isInstanceOf(Transport.class);
    }

    @Test
    void theRecorderPassesEverythingThrough() {
        RecordingTransport recorder = new RecordingTransport(new LoopbackTransport());
        BitVector tdi = bits("1100");
        assertThat(recorder.pulse(BitVector.zeros(4), tdi)).isEqualTo(tdi);
    }

    @Test
    void theRecorderKeepsWhatWentOut() {
        RecordingTransport recorder = new RecordingTransport(new LoopbackTransport());
        recorder.pulse(bits("11"), bits("01"));
        recorder.pulse(bits("0000"), bits("1010"));
        assertThat(recorder.operations()).hasSize(2);
        assertThat(recorder.clocks()).isEqualTo(6);
        assertThat(recorder.operations().get(0).tdo()).isEqualTo(bits("01"));
    }

    @Test
    void theTranscriptReadsLikeALogLine() {
        RecordingTransport recorder = new RecordingTransport(new LoopbackTransport());
        recorder.pulse(bits("11"), bits("01"));
        assertThat(recorder.transcript()).containsExactly("2 clocks tms=11 tdi=01 tdo=01");
    }

    @Test
    void theRecorderNamesWhatItWraps() {
        assertThat(new RecordingTransport(new LoopbackTransport()).name()).isEqualTo("recording(loopback)");
    }

    @Test
    void theRecorderStopsGrowingAtItsLimit() {
        RecordingTransport recorder = new RecordingTransport(new LoopbackTransport(), 3);
        for (int pulse = 0; pulse < 10; pulse++) {
            recorder.pulse(bits("1"), bits("0"));
        }
        assertThat(recorder.operations()).hasSize(3);
        assertThat(recorder.truncated()).isTrue();
        assertThat(recorder.transcript()).last().isEqualTo("transcript truncated");
    }

    @Test
    void aShortTranscriptIsNotMarkedTruncated() {
        RecordingTransport recorder = new RecordingTransport(new LoopbackTransport(), 3);
        recorder.pulse(bits("1"), bits("0"));
        assertThat(recorder.truncated()).isFalse();
        List<String> transcript = recorder.transcript();
        assertThat(transcript.get(transcript.size() - 1)).doesNotContain("truncated");
    }

    @Test
    void clearingTheRecorderLeavesTheTransportAlone() {
        LoopbackTransport inner = new LoopbackTransport();
        RecordingTransport recorder = new RecordingTransport(inner);
        recorder.pulse(bits("1"), bits("1"));
        recorder.clear();
        assertThat(recorder.operations()).isEmpty();
        assertThat(recorder.clocks()).isZero();
        assertThat(inner.closed()).isFalse();
    }

    @Test
    void closingTheRecorderClosesWhatItWraps() {
        LoopbackTransport inner = new LoopbackTransport();
        new RecordingTransport(inner).close();
        assertThat(inner.closed()).isTrue();
    }

    @Test
    void theClockGoesThroughToTheRealTransport() {
        LoopbackTransport inner = new LoopbackTransport();
        new RecordingTransport(inner).setClock(Frequency.megahertz(15));
        assertThat(inner.clock()).isEqualTo(Frequency.megahertz(15));
    }

    @Test
    void anOperationCountsItsOwnClocks() {
        ScanOperation operation = new ScanOperation(bits("110"), bits("000"), bits("001"));
        assertThat(operation.clocks()).isEqualTo(3);
    }
}
