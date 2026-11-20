package io.github.rkaushikdev.carrierworks.bitstream;

import java.util.List;
import java.util.Optional;

/**
 * One packet out of a configuration stream.
 *
 * @param kind the packet type, one or two
 * @param opcode what the packet does to its register
 * @param register the register address; a type two packet before any type one has none
 * @param words the payload, as unsigned 32 bit words; empty when it was too long to keep
 */
public record ConfigPacket(int kind, Opcode opcode, Optional<Integer> register, List<Long> words) {

    public ConfigPacket {
        words = List.copyOf(words);
    }

    /** The register's name, {@code none} when the packet has no register. */
    public String registerName() {
        return register.map(Packets::registerName).orElse("none");
    }

    /** How many payload words were kept. */
    public int wordCount() {
        return words.size();
    }

    /** Whether the packet writes its register. */
    public boolean isWrite() {
        return opcode == Opcode.WRITE;
    }

    /** {@code type1 write idcode x1}: the packet as one line of a stream dump. */
    public String describe() {
        return "type" + kind + " " + opcode + " " + registerName() + " x" + wordCount();
    }
}
