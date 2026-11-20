package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Frequency;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * A transport that answers the way a real chain would, without any silicon.
 *
 * <p>This is not a toy. Every piece of the bench above the transport is written against a real
 * chain and there is no way to test any of it honestly without something that shifts bits the way
 * devices do, including the parts that are easy to get wrong: the capture of {@code 01} into the
 * instruction register, the single bit a bypassed device contributes, and the fact that the last
 * data bit goes out on the same clock that leaves the shift state.
 *
 * <p>The whole chain is one shift register. Bits go into the device nearest TDI and come out of the
 * device nearest TDO, so the simulator holds the concatenated register with the TDO end at the
 * front and pushes new bits on at the back. That is the entire trick, and everything else is
 * bookkeeping around it.
 */
public final class ChainSimulator implements Transport {

    private final List<SimulatedDevice> devices;
    private TapState state = TapState.TEST_LOGIC_RESET;
    private Frequency clock = Frequency.megahertz(1);
    private boolean closed;
    private final Deque<Integer> register = new ArrayDeque<>();
    private BitVector.PackedBits stream = new BitVector.PackedBits();

    /** A chain of these devices, listed in TDI order. */
    public ChainSimulator(List<SimulatedDevice> devices) {
        if (devices.isEmpty()) {
            throw new ValidationError("a simulated chain needs a device", "devices");
        }
        this.devices = List.copyOf(devices);
        reset();
    }

    /** A chain of catalogued parts, labelled {@code u1}, {@code u2} and so on in TDI order. */
    public static ChainSimulator ofDevices(String... devices) {
        List<SimulatedDevice> built = new ArrayList<>(devices.length);
        for (int index = 0; index < devices.length; index++) {
            built.add(SimulatedDevice.forDevice("u" + (index + 1), devices[index]));
        }
        return new ChainSimulator(built);
    }

    /**
     * A simulator matching a described chain.
     *
     * <p>A position that is not a catalogued device is still simulated as long as the chain says
     * what it answers with, which is how the system controllers on the carriers get represented:
     * the bench does not stock them as devices, but it knows their idcode and their register width.
     */
    public static ChainSimulator forChain(ScanChain chain) {
        List<SimulatedDevice> built = new ArrayList<>(chain.size());
        for (TapPosition tap : chain.taps()) {
            if (tap.device().isPresent()) {
                built.add(SimulatedDevice.forDevice(tap.label(), tap.device().get()));
            } else if (tap.expectedIdcode().isPresent()) {
                built.add(new SimulatedDevice(tap.label(), tap.expectedIdcode().get(), tap.irLength()));
            } else {
                throw new ValidationError(
                        "a simulated chain cannot hold a position with no idcode",
                        "chain",
                        Details.of("tap", tap.label()));
            }
        }
        return new ChainSimulator(built);
    }

    @Override
    public String name() {
        return "simulator";
    }

    /** The rate it was last told to run at, one megahertz until then. */
    public Frequency clock() {
        return clock;
    }

    @Override
    public void setClock(Frequency clock) {
        this.clock = clock;
    }

    /** Whether it has been closed. */
    public boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
    }

    /** Where the simulated controller is. */
    public TapState state() {
        return state;
    }

    /** Every device on the chain, in TDI order. */
    public List<SimulatedDevice> devices() {
        return devices;
    }

    /** Put every device and the controller back the way a tap reset leaves them. */
    public void reset() {
        for (SimulatedDevice device : devices) {
            device.reset();
        }
        state = TapState.TEST_LOGIC_RESET;
        register.clear();
        stream = new BitVector.PackedBits();
    }

    /** The device with exactly this label. */
    public SimulatedDevice device(String label) {
        return devices.stream()
                .filter(device -> device.label().equals(label))
                .findFirst()
                .orElseThrow(() -> new ValidationError(
                        "no such device on the simulated chain", "label", Details.of("given", label)));
    }

    @Override
    public BitVector pulse(BitVector tms, BitVector tdi) {
        if (closed) {
            throw new ValidationError("transport is closed", "transport");
        }
        if (tms.length() != tdi.length()) {
            throw new ValidationError("tms and tdi have to be the same length", "pulse");
        }
        if (tms.length() == 0) {
            throw new ValidationError("a pulse clocks at least one bit", "pulse");
        }
        BitVector.PackedBits tdo = new BitVector.PackedBits();
        for (int index = 0; index < tms.length(); index++) {
            tdo.append(clock(tms.bit(index) == 1, tdi.bit(index)) == 1);
        }
        return tdo.toVector();
    }

    /** One clock: shift if the controller is in a shift state, then move it and act on arrival. */
    private int clock(boolean tms, int tdi) {
        int out = 0;
        if (state.isShiftState()) {
            Integer first = register.pollFirst();
            out = first == null ? 0 : first;
            register.addLast(tdi);
            if (state == TapState.SHIFT_DR) {
                stream.append(tdi == 1);
            }
        }
        TapState next = state.step(tms);
        if (next == TapState.TEST_LOGIC_RESET) {
            reset();
            return out;
        }
        switch (next) {
            case CAPTURE_DR -> loadDr();
            case CAPTURE_IR -> loadIr();
            case UPDATE_DR -> applyDr();
            case UPDATE_IR -> applyIr();
            default -> {
                // every other state only moves the controller
            }
        }
        state = next;
        return out;
    }

    private List<SimulatedDevice> tdoOrder() {
        return devices.reversed();
    }

    private void loadDr() {
        stream = new BitVector.PackedBits();
        load(tdoOrder().stream().map(SimulatedDevice::drCapture).toList());
    }

    private void loadIr() {
        load(tdoOrder().stream().map(SimulatedDevice::irCapture).toList());
    }

    private void load(List<BitVector> captures) {
        register.clear();
        register.addAll(BitVector.join(captures).bits());
    }

    /**
     * The chain register cut up per device, or empty if it does not fit.
     *
     * <p>A short register means the caller shifted fewer bits than the chain holds, which a real
     * device would treat as a partial update. The simulator does the same thing a device does and
     * ignores it.
     */
    private Optional<List<BitVector>> shifted(List<Integer> widths) {
        BitVector held = BitVector.fromBits(register);
        if (held.length() != widths.stream().mapToInt(Integer::intValue).sum()) {
            return Optional.empty();
        }
        return Optional.of(held.split(widths));
    }

    private void applyIr() {
        List<SimulatedDevice> order = tdoOrder();
        shifted(order.stream().map(SimulatedDevice::irLength).toList()).ifPresent(pieces -> {
            for (int index = 0; index < order.size(); index++) {
                order.get(index).applyInstruction(pieces.get(index).toLong());
            }
        });
    }

    private void applyDr() {
        List<SimulatedDevice> order = tdoOrder();
        List<SimulatedDevice> streaming = order.stream()
                .filter(device -> device.instruction() == SimulatedDevice.CFG_IN)
                .toList();
        if (!streaming.isEmpty()) {
            // A configuration write is a stream of any length rather than a register of a known
            // one, so it is taken from what was shifted in rather than from what the chain
            // register held.
            BitVector written = stream.toVector();
            for (SimulatedDevice device : streaming) {
                device.takeConfig(written);
                device.recordDrWrite(written);
            }
            return;
        }
        shifted(order.stream().map(SimulatedDevice::drLength).toList()).ifPresent(pieces -> {
            for (int index = 0; index < order.size(); index++) {
                order.get(index).recordDrWrite(pieces.get(index));
            }
        });
    }
}
