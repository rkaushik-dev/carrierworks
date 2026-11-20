package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.catalog.DeviceSpec;
import io.github.rkaushikdev.carrierworks.catalog.Devices;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.Set;

/**
 * One device on a simulated chain: its registers, its current instruction, and whatever
 * configuration has been shifted into it.
 *
 * <p>The class is open so a test can stand in a device that misbehaves, such as one that takes an
 * image and never comes up, by overriding the one step it gets wrong.
 */
public class SimulatedDevice {

    /** Bit fourteen of the status register is done, on every seven series part. */
    public static final long DONE_BIT = 1L << 14;

    /** The instruction under which a data register write is configuration, of any length. */
    static final long CFG_IN = Scan.INSTRUCTIONS.get("cfg_in");

    private static final long IDCODE = Scan.INSTRUCTIONS.get("idcode");
    private static final long USERCODE = Scan.INSTRUCTIONS.get("usercode");
    private static final long CFG_OUT = Scan.INSTRUCTIONS.get("cfg_out");
    private static final long JPROGRAM = Scan.INSTRUCTIONS.get("jprogram");
    private static final long JSTART = Scan.INSTRUCTIONS.get("jstart");
    private static final long BYPASS = Scan.INSTRUCTIONS.get("bypass");
    private static final Set<Long> USER_REGISTERS = Set.of(
            Scan.INSTRUCTIONS.get("user1"),
            Scan.INSTRUCTIONS.get("user2"),
            Scan.INSTRUCTIONS.get("user3"),
            Scan.INSTRUCTIONS.get("user4"));

    private static final int BYPASS_BITS = 1;
    private static final int IDCODE_BITS = 32;
    private static final long CAPTURED_IR = 0b01;

    private final String label;
    private final long idcode;
    private final int irLength;
    private long usercode = 0xFFFF_FFFFL;
    private int userRegisterBits = 32;
    private long instruction = IDCODE;
    private BitVector lastDrWrite;
    private final ByteArrayOutputStream configData = new ByteArrayOutputStream();
    private boolean configured;

    /** A device that answers with this idcode and has an instruction register this wide. */
    public SimulatedDevice(String label, long idcode, int irLength) {
        this.label = label;
        this.idcode = idcode;
        this.irLength = irLength;
    }

    /** A catalogued device, with the idcode and width the catalog gives it. */
    public static SimulatedDevice forDevice(String label, String device) {
        DeviceSpec spec = Devices.deviceFor(device);
        return new SimulatedDevice(label, spec.idcode(), spec.irLength());
    }

    /** Its label on the chain. */
    public String label() {
        return label;
    }

    /** What it captures under the idcode instruction. */
    public long idcode() {
        return idcode;
    }

    /** How wide its instruction register is. */
    public int irLength() {
        return irLength;
    }

    /** What it captures under the usercode instruction; all ones until somebody sets one. */
    public long usercode() {
        return usercode;
    }

    /** Give it a usercode, the way a loaded design would. */
    public void setUsercode(long usercode) {
        this.usercode = usercode;
    }

    /** How wide its four user data registers are. */
    public int userRegisterBits() {
        return userRegisterBits;
    }

    /** Make its user data registers this wide. */
    public void setUserRegisterBits(int userRegisterBits) {
        this.userRegisterBits = userRegisterBits;
    }

    /** The instruction it currently holds. */
    public long instruction() {
        return instruction;
    }

    /** The last data register write that reached it, if any since the last reset. */
    public Optional<BitVector> lastDrWrite() {
        return Optional.ofNullable(lastDrWrite);
    }

    /** Record a data register write as having reached it. */
    void recordDrWrite(BitVector written) {
        this.lastDrWrite = written;
    }

    /** Every configuration byte shifted in since the last jprogram. A copy. */
    public byte[] configData() {
        return configData.toByteArray();
    }

    /** Whether a jstart has brought the device up with a configuration in it. */
    public boolean configured() {
        return configured;
    }

    /**
     * A tap reset puts the instruction back but leaves the device configured.
     *
     * <p>That is what the silicon does, and it matters: a chain rescan in the middle of a session
     * does not wipe the image somebody just loaded.
     */
    public void reset() {
        instruction = IDCODE;
        lastDrWrite = null;
    }

    /** Whether it holds the bypass instruction. */
    public boolean inBypass() {
        return instruction == BYPASS;
    }

    /** How wide its data register is under the current instruction. */
    public int drLength() {
        if (instruction == IDCODE || instruction == USERCODE || instruction == CFG_OUT) {
            return IDCODE_BITS;
        }
        if (USER_REGISTERS.contains(instruction)) {
            return userRegisterBits;
        }
        return BYPASS_BITS;
    }

    /** What this device loads into the data register on capture. */
    public BitVector drCapture() {
        if (instruction == IDCODE) {
            return new BitVector(idcode, IDCODE_BITS);
        }
        if (instruction == USERCODE) {
            return new BitVector(usercode, IDCODE_BITS);
        }
        if (instruction == CFG_OUT) {
            return new BitVector(statusWord(), IDCODE_BITS);
        }
        return BitVector.zeros(drLength());
    }

    /** The status register, with the done bit where the silicon puts it. */
    public long statusWord() {
        return configured ? DONE_BIT : 0;
    }

    /**
     * Take an instruction and the side effects it has on configuration.
     *
     * <p>Loading jprogram clears the device, jstart runs whatever has been shifted in, and everything
     * else leaves the configuration alone.
     */
    public void applyInstruction(long instruction) {
        if (instruction == JPROGRAM) {
            configData.reset();
            configured = false;
        } else if (instruction == JSTART && configData.size() > 0) {
            configured = true;
        }
        this.instruction = instruction;
    }

    /** Take a configuration write, appending its bytes to what has been shifted in so far. */
    public void takeConfig(BitVector written) {
        configData.writeBytes(written.toBytes());
    }

    /** What the instruction register captures: {@code 01} in the low bits, as the standard requires. */
    public BitVector irCapture() {
        return new BitVector(CAPTURED_IR, irLength);
    }
}
