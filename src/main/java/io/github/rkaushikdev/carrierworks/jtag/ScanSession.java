package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.ChainError;
import io.github.rkaushikdev.carrierworks.Frequency;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.catalog.IdCode;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One conversation with one chain, from reset to release.
 *
 * <p>The session owns two things the layers underneath deliberately do not: where the controller
 * currently is, and which chain it is talking to. Everything it offers is spelled as an operation
 * somebody in the lab would recognise, so the code that programs a device reads as a list of scans
 * rather than as a list of TMS runs.
 *
 * <p>It always starts from reset. A session that inherits whatever state the last one left behind
 * works perfectly until the day it does not, and the failure looks like a bad cable rather than
 * like a bug. Closing the session closes its transport.
 */
public final class ScanSession implements AutoCloseable {

    private final Transport transport;
    private final ScanChain chain;
    private TapState state = TapState.TEST_LOGIC_RESET;
    private long clocks;

    /** A session over a transport left at whatever clock it already runs at. */
    public ScanSession(Transport transport, ScanChain chain) {
        this.transport = transport;
        this.chain = chain;
        reset();
    }

    /** A session that sets the transport's clock before the first reset goes out. */
    public ScanSession(Transport transport, ScanChain chain, Frequency clock) {
        this.transport = transport;
        this.chain = chain;
        transport.setClock(clock);
        reset();
    }

    /** What the session clocks bits through. */
    public Transport transport() {
        return transport;
    }

    /** The chain the session believes it is talking to. */
    public ScanChain chain() {
        return chain;
    }

    /** Where the controller is now. */
    public TapState state() {
        return state;
    }

    /** Every clock the session has sent, the opening reset included. */
    public long clocks() {
        return clocks;
    }

    @Override
    public void close() {
        transport.close();
    }

    private BitVector pulse(BitVector tms, BitVector tdi) {
        clocks += tms.length();
        return transport.pulse(tms, tdi);
    }

    /** Five clocks with TMS high, which lands in reset from anywhere. */
    public void reset() {
        pulse(BitVector.ones(Tap.RESET_CLOCKS), BitVector.zeros(Tap.RESET_CLOCKS));
        state = TapState.TEST_LOGIC_RESET;
    }

    /** Take the controller to a state by the shortest path, TDI held low. */
    public void moveTo(TapState target) {
        List<Integer> path = Tap.pathBetween(state, target);
        if (!path.isEmpty()) {
            pulse(BitVector.fromBits(path), BitVector.zeros(path.size()));
        }
        state = target;
    }

    /** Sit in idle for a while, which is how a device is given time. */
    public void runTestIdle(int clocks) {
        if (clocks < 0) {
            throw new ValidationError("cannot idle for negative clocks", "clocks");
        }
        moveTo(TapState.RUN_TEST_IDLE);
        if (clocks > 0) {
            pulse(BitVector.zeros(clocks), BitVector.zeros(clocks));
        }
    }

    /**
     * Shift a payload through one of the two shift states and come back to idle.
     *
     * <p>TMS goes high on the last bit, so the last data bit goes out on the clock that leaves the
     * shift state.
     */
    private BitVector shift(TapState shiftState, BitVector payload) {
        int length = payload.length();
        if (length == 0) {
            throw new ValidationError("nothing to shift", "payload");
        }
        moveTo(shiftState);
        BitVector tms = new BitVector(BigInteger.ONE.shiftLeft(length - 1), length);
        BitVector captured = pulse(tms, payload);
        state = shiftState == TapState.SHIFT_IR ? TapState.EXIT1_IR : TapState.EXIT1_DR;
        moveTo(TapState.RUN_TEST_IDLE);
        return captured;
    }

    /** Put every device on the chain in bypass, and return what the instruction registers captured. */
    public BitVector shiftIr() {
        return shiftIr(Map.of());
    }

    /**
     * Load an instruction into every device on the chain, bypass for any not named, and return what
     * the instruction registers captured.
     *
     * @param instructions instruction names by position label
     */
    public BitVector shiftIr(Map<String, String> instructions) {
        return shift(TapState.SHIFT_IR, Scan.irScan(chain, instructions));
    }

    /** Shift a payload at one device and return what that device gave back. */
    public BitVector shiftDr(String label, BitVector payload) {
        BitVector whole = shift(TapState.SHIFT_DR, Scan.drScan(chain, label, payload));
        return Scan.takeDrPayload(chain, label, whole, payload.length());
    }

    /** The raw discovery scan straight after a reset: ones in, whatever comes out. */
    public BitVector scanIdcodes() {
        return scanIdcodes(true);
    }

    /**
     * The raw discovery scan, ones in, whatever comes out.
     *
     * <p>Skipping the reset is how a caller asks what the chain is doing right now rather than what
     * it holds when it is left alone, and the bypass check in the reader depends on it.
     *
     * @param reset whether to reset the chain first
     */
    public BitVector scanIdcodes(boolean reset) {
        if (reset) {
            reset();
        }
        int width = chain.size() * Discover.IDCODE_BITS + Discover.IDCODE_BITS;
        return shift(TapState.SHIFT_DR, BitVector.ones(width));
    }

    /** The idcodes on the chain after a reset, in the listed order rather than shift order. */
    public List<IdCode> readIdcodes() {
        return readIdcodes(true);
    }

    /**
     * The idcodes on the chain, in the listed order rather than shift order.
     *
     * <p>A device that answers in bypass has no idcode to give, and that is refused as a chain
     * fault rather than skipped.
     *
     * @param reset whether to reset the chain first
     */
    public List<IdCode> readIdcodes(boolean reset) {
        List<DiscoveredTap> found = Discover.discoverTaps(scanIdcodes(reset));
        List<IdCode> codes = new ArrayList<>(found.size());
        for (DiscoveredTap tap : found.reversed()) {
            codes.add(tap.idcode().orElseThrow(() -> new ChainError("a device on the chain answered in bypass")));
        }
        return List.copyOf(codes);
    }

    /** Check the chain against the one on file and return what answered. */
    public ScanChain verify() {
        return Verify.verifyChain(chain, scanIdcodes());
    }

    /** One device gets an instruction and everything else bypasses. */
    public void loadInstruction(String label, String instruction) {
        chain.indexOf(label);
        shiftIr(Map.of(label, instruction));
    }
}
