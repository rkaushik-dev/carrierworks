package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Building the vectors that actually go out on the wire.
 *
 * <p>Two rules cover everything here. A device that is not the target of a scan gets the bypass
 * instruction, which is all ones whatever the width, and in a data register scan it contributes
 * exactly one bit. And the first bit shifted in travels furthest, so it belongs to the device
 * nearest TDO, which is the last one in the listed order.
 *
 * <p>Get the second rule backwards and everything still runs. The scans are the right length, the
 * driver is happy, and the answers are for the wrong device. That is why the tests spell out bit
 * patterns by hand rather than round tripping through these same methods.
 */
public final class Scan {

    /**
     * Seven series instruction opcodes, six bits wide, by name.
     *
     * <p>Held as {@code long} because an instruction register can be as wide as thirty two bits, and
     * what a device holds in one is compared against these.
     */
    public static final Map<String, Long> INSTRUCTIONS = instructions();

    private Scan() {}

    private static Map<String, Long> instructions() {
        Map<String, Long> codes = new LinkedHashMap<>();
        codes.put("extest", 0b100110L);
        codes.put("sample", 0b000001L);
        codes.put("preload", 0b000001L);
        codes.put("idcode", 0b001001L);
        codes.put("usercode", 0b001000L);
        codes.put("user1", 0b000010L);
        codes.put("user2", 0b000011L);
        codes.put("user3", 0b100010L);
        codes.put("user4", 0b100011L);
        codes.put("cfg_out", 0b000100L);
        codes.put("cfg_in", 0b000101L);
        codes.put("jprogram", 0b001011L);
        codes.put("jstart", 0b001100L);
        codes.put("jshutdown", 0b001101L);
        codes.put("bypass", 0b111111L);
        return Collections.unmodifiableMap(codes);
    }

    private static long opcode(String name) {
        Long code = INSTRUCTIONS.get(name);
        if (code == null) {
            throw new NotFound("instruction", name);
        }
        return code;
    }

    /** All ones, which is bypass on every device that follows the standard. */
    public static BitVector bypassInstruction(int irLength) {
        return BitVector.ones(irLength);
    }

    /**
     * One named instruction, as bits of the right width for its device.
     *
     * <p>Bypass widens to whatever the device needs; any other opcode has to fit the width as it
     * is.
     */
    public static BitVector instructionCode(String name, int irLength) {
        String key = name.strip().toLowerCase(Locale.ROOT);
        long code = opcode(key);
        if (key.equals("bypass")) {
            return bypassInstruction(irLength);
        }
        if (Long.SIZE - Long.numberOfLeadingZeros(code) > irLength) {
            throw new ValidationError(
                    "instruction does not fit that device",
                    "instruction",
                    Details.of("given", name, "ir_length", irLength));
        }
        return new BitVector(code, irLength);
    }

    /** The instruction register scan that puts every device on the chain in bypass. */
    public static BitVector irScan(ScanChain chain) {
        return irScan(chain, Map.of());
    }

    /**
     * The whole instruction register scan for a chain, with the named positions given their
     * instructions.
     *
     * <p>Anything not named gets bypass. The vector is in shift order, so the bits for the device
     * nearest TDO come first.
     *
     * @param instructions instruction names by position label
     */
    public static BitVector irScan(ScanChain chain, Map<String, String> instructions) {
        Map<String, String> wanted = new LinkedHashMap<>();
        instructions.forEach((label, instruction) -> wanted.put(label.strip().toLowerCase(Locale.ROOT), instruction));
        TreeSet<String> unknown = new TreeSet<>(wanted.keySet());
        chain.labels().forEach(unknown::remove);
        if (!unknown.isEmpty()) {
            throw new NotFound("tap", String.join(", ", unknown));
        }
        List<BitVector> parts = chain.inTdoOrder().stream()
                .map(tap -> instructionCode(wanted.getOrDefault(tap.label(), "bypass"), tap.irLength()))
                .toList();
        return BitVector.join(parts);
    }

    /** How many bypass bits arrive before the device's own data. */
    public static int drCaptureOffset(ScanChain chain, String label) {
        return chain.devicesBeforeTdo(chain.indexOf(label));
    }

    /**
     * A data register scan carrying a payload for one device.
     *
     * <p>Every other device is in bypass and contributes a single bit, so the scan is the payload
     * with one bit per device on either side of it.
     */
    public static BitVector drScan(ScanChain chain, String label, BitVector payload) {
        int index = chain.indexOf(label);
        return BitVector.join(List.of(
                BitVector.zeros(chain.devicesBeforeTdo(index)),
                payload,
                BitVector.zeros(chain.devicesAfterTdo(index))));
    }

    /** Pull one device's answer, {@code length} bits of it, out of a whole chain capture. */
    public static BitVector takeDrPayload(ScanChain chain, String label, BitVector captured, int length) {
        int index = chain.indexOf(label);
        int offset = chain.devicesBeforeTdo(index);
        int expected = offset + length + chain.devicesAfterTdo(index);
        if (captured.length() != expected) {
            throw new ValidationError(
                    "capture is not the length this chain would produce",
                    "captured",
                    Details.of("expected", expected, "given", captured.length()));
        }
        return captured.slice(offset, length);
    }
}
