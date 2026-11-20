package io.github.rkaushikdev.carrierworks.bitstream;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;

/** The two bit operation field of a configuration packet header. */
public enum Opcode {
    NOP(0, "nop"),
    READ(1, "read"),
    WRITE(2, "write"),
    RESERVED(3, "reserved");

    private final int code;
    private final String value;

    Opcode(int code, String value) {
        this.code = code;
        this.value = value;
    }

    /** The number the header carries. */
    public int code() {
        return code;
    }

    /** The name a packet description prints. */
    public String value() {
        return value;
    }

    /**
     * The operation behind a header field.
     *
     * @throws ValidationError when the number is not one of the four the field can hold
     */
    public static Opcode of(int code) {
        for (Opcode opcode : values()) {
            if (opcode.code == code) {
                return opcode;
            }
        }
        throw new ValidationError("no such opcode", "opcode", Details.of("given", code));
    }

    @Override
    public String toString() {
        return value;
    }
}
