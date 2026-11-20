package io.github.rkaushikdev.carrierworks.jtag;

import java.util.Optional;

/**
 * One way the chain that answered is not the chain that was expected.
 *
 * @param kind what sort of difference it is
 * @param position the zero based position in TDI order, or empty when it is about the whole chain
 * @param expected what should have been there
 * @param found what was there instead
 */
public record ChainDifference(Kind kind, Optional<Integer> position, String expected, String found) {

    /** The three shapes a wrong chain comes in, which point at three different causes. */
    public enum Kind {
        /** The chain is the wrong length: a device missing, an extra one, or a cable working loose. */
        LENGTH("length"),
        /** A device that belongs on this chain, in the wrong place: usually the wrong header. */
        ORDER("order"),
        /** A device that does not belong on this chain at all: usually the wrong part fitted. */
        DEVICE("device");

        private final String value;

        Kind(String value) {
            this.value = value;
        }

        /** The name the kind is printed under. */
        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /** A difference about the length of the whole chain. */
    public static ChainDifference wrongLength(int expected, int found) {
        return new ChainDifference(Kind.LENGTH, Optional.empty(), String.valueOf(expected), String.valueOf(found));
    }

    /**
     * One line for a log, with positions counted from one: {@code position 2 holds xc7a35t, expected
     * xc7k325t}.
     */
    public String describe() {
        String where = position.map(index -> "position " + (index + 1)).orElse("the chain");
        return switch (kind) {
            case LENGTH -> where + " has " + found + " devices, expected " + expected;
            case ORDER -> where + " holds " + found + ", which belongs elsewhere on this chain";
            case DEVICE -> where + " holds " + found + ", expected " + expected;
        };
    }
}
