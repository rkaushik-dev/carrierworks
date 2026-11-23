package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.PduChannel;
import io.github.rkaushikdev.carrierworks.jtag.AdapterUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Board builders the tests share.
 *
 * <p>Two carriers that exist in the lab, spelled once. Every test that needs a board needs one
 * that passes the board's own checks, and repeating that description in six files is how one of
 * them ends up subtly different from the rest. Each starts from the lab's real carrier and lets a
 * test change only what it is about.
 */
public final class BoardSetup {

    private BoardSetup() {}

    /** The capture carrier: a Kintex with a four channel converter on it. */
    public static Carrier kintex(String asset) {
        return new Carrier(
                asset,
                "rx-carrier-b",
                "XC7K325T-2FFG900I",
                "n25q128a13",
                "rack-2/shelf-04/slot-3",
                "210308AB1234",
                7,
                "pdu-a",
                "jtag-hs2",
                List.of(new BoardSlot("fmc0", "fmc-hpc", "fmc-adc-4ch-14b"), new BoardSlot("fmc1", "fmc-lpc")));
    }

    /** {@link #kintex(String)} as tag {@code bx-14}, built. */
    public static Board kintex() {
        return kintex("bx-14").build();
    }

    /** The small carrier: an Artix with a pmod and a spare slot. */
    public static Carrier artix(String asset) {
        return new Carrier(
                asset,
                "tx-carrier-a",
                "XC7A100T-2FGG484I",
                "w25q64jv",
                "rack-4/shelf-01/slot-1",
                "210308CD5678",
                9,
                "pdu-b",
                "jtag-hs3",
                List.of(new BoardSlot("pmod0", "pmod", "pmod-ad2"), new BoardSlot("fmc0", "fmc-lpc")));
    }

    /** {@link #artix(String)} as tag {@code bx-15}, built. */
    public static Board artix() {
        return artix("bx-15").build();
    }

    /** One of the lab's carriers, with whatever a test wants changed about it. */
    public static final class Carrier {

        private final String asset;
        private final String model;
        private String part;
        private String flash;
        private String location;
        private Optional<String> serial;
        private Optional<Integer> outlet;
        private String pduName;
        private String adapterModel;
        private List<BoardSlot> slots;
        private UnaryOperator<Board.Builder> rest = UnaryOperator.identity();

        private Carrier(
                String asset,
                String model,
                String part,
                String flash,
                String location,
                String serial,
                int outlet,
                String pduName,
                String adapterModel,
                List<BoardSlot> slots) {
            this.asset = asset;
            this.model = model;
            this.part = part;
            this.flash = flash;
            this.location = location;
            this.serial = Optional.of(serial);
            this.outlet = Optional.of(outlet);
            this.pduName = pduName;
            this.adapterModel = adapterModel;
            this.slots = slots;
        }

        /** The part fitted, as its ordering code. */
        public Carrier part(String value) {
            part = value;
            return this;
        }

        /** The configuration flash part fitted. */
        public Carrier flash(String value) {
            flash = value;
            return this;
        }

        public Carrier location(String value) {
            location = value;
            return this;
        }

        /** The cable's serial. */
        public Carrier serial(String value) {
            serial = Optional.of(value);
            return this;
        }

        /** No cable on the board. */
        public Carrier noCable() {
            serial = Optional.empty();
            return this;
        }

        /** The outlet feeding the board. */
        public Carrier outlet(int value) {
            outlet = Optional.of(value);
            return this;
        }

        /** No outlet on record. */
        public Carrier noOutlet() {
            outlet = Optional.empty();
            return this;
        }

        /** Neither a cable nor an outlet. */
        public Carrier bare() {
            return noCable().noOutlet();
        }

        public Carrier pduName(String value) {
            pduName = value;
            return this;
        }

        public Carrier adapterModel(String value) {
            adapterModel = value;
            return this;
        }

        public Carrier slots(BoardSlot... value) {
            slots = List.of(value);
            return this;
        }

        /** Anything else about the board, set straight on its builder. */
        public Carrier with(UnaryOperator<Board.Builder> change) {
            UnaryOperator<Board.Builder> before = rest;
            rest = builder -> change.apply(before.apply(builder));
            return this;
        }

        /** The board, checked. */
        public Board build() {
            Board.Builder builder =
                    Board.builder(asset, model, part, flash, location).slots(slots);
            serial.ifPresent(value -> builder.adapter(new AdapterUnit(value, adapterModel)));
            outlet.ifPresent(value -> builder.pdu(new PduChannel(pduName, value)));
            return rest.apply(builder).build();
        }
    }
}
