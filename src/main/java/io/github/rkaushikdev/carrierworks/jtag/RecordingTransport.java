package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.Frequency;
import java.util.ArrayList;
import java.util.List;

/**
 * Wraps a transport and keeps every operation that went through it.
 *
 * <p>Recording matters as much as driving. When a programming job fails at two in the morning, the
 * thing worth having is the exact sequence that went out, which is why the recorder wraps any
 * transport rather than being a feature of one. It stops keeping operations at its limit, so a long
 * configuration load cannot fill the memory of the worker running it, and the transcript says when
 * that has happened.
 */
public final class RecordingTransport implements Transport {

    /** How many operations a recorder keeps unless told otherwise. */
    public static final int DEFAULT_LIMIT = 4096;

    private final Transport inner;
    private final int limit;
    private final List<ScanOperation> operations = new ArrayList<>();

    /** A recorder keeping up to {@link #DEFAULT_LIMIT} operations. */
    public RecordingTransport(Transport inner) {
        this(inner, DEFAULT_LIMIT);
    }

    /** A recorder keeping up to {@code limit} operations. */
    public RecordingTransport(Transport inner, int limit) {
        this.inner = inner;
        this.limit = limit;
    }

    /** {@code recording(loopback)}. */
    @Override
    public String name() {
        return "recording(" + inner.name() + ")";
    }

    @Override
    public void setClock(Frequency clock) {
        inner.setClock(clock);
    }

    @Override
    public BitVector pulse(BitVector tms, BitVector tdi) {
        BitVector tdo = inner.pulse(tms, tdi);
        if (operations.size() < limit) {
            operations.add(new ScanOperation(tms, tdi, tdo));
        }
        return tdo;
    }

    @Override
    public void close() {
        inner.close();
    }

    /** Every operation kept so far, oldest first. */
    public List<ScanOperation> operations() {
        return List.copyOf(operations);
    }

    /** Every clock that has gone out, which is what a job reports. */
    public long clocks() {
        return operations.stream().mapToLong(ScanOperation::clocks).sum();
    }

    /** Whether the recorder has reached its limit and stopped keeping operations. */
    public boolean truncated() {
        return operations.size() >= limit;
    }

    /** One line per operation kept, with a closing line when the limit cut the record short. */
    public List<String> transcript() {
        List<String> lines =
                new ArrayList<>(operations.stream().map(ScanOperation::describe).toList());
        if (truncated()) {
            lines.add("transcript truncated");
        }
        return List.copyOf(lines);
    }

    /** Forget every operation kept so far, leaving the transport underneath alone. */
    public void clear() {
        operations.clear();
    }
}
