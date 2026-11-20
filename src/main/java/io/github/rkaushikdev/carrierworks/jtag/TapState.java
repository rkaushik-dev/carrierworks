package io.github.rkaushikdev.carrierworks.jtag;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Arrays;

/**
 * The sixteen states of the test access port controller, and the one clock that moves between them.
 *
 * <p>Every JTAG operation is a walk through this machine clocked by one wire. Each state has
 * exactly two ways out, one for TMS low and one for TMS high, and the table of them is the whole of
 * IEEE 1149.1's controller. The constants are listed in the order the standard draws them, data
 * register column before instruction register column.
 */
public enum TapState {
    TEST_LOGIC_RESET("test_logic_reset"),
    RUN_TEST_IDLE("run_test_idle"),
    SELECT_DR("select_dr"),
    CAPTURE_DR("capture_dr"),
    SHIFT_DR("shift_dr"),
    EXIT1_DR("exit1_dr"),
    PAUSE_DR("pause_dr"),
    EXIT2_DR("exit2_dr"),
    UPDATE_DR("update_dr"),
    SELECT_IR("select_ir"),
    CAPTURE_IR("capture_ir"),
    SHIFT_IR("shift_ir"),
    EXIT1_IR("exit1_ir"),
    PAUSE_IR("pause_ir"),
    EXIT2_IR("exit2_ir"),
    UPDATE_IR("update_ir");

    private final String value;

    TapState(String value) {
        this.value = value;
    }

    /** The name the state is printed and asked for under, such as {@code shift_dr}. */
    public String value() {
        return value;
    }

    /** The state with exactly this name; names are not normalised, so {@code SHIFT_DR} is refused. */
    public static TapState parse(String text) {
        return Arrays.stream(values())
                .filter(state -> state.value.equals(text))
                .findFirst()
                .orElseThrow(() -> new ValidationError("no such tap state", "state", Details.of("given", text)));
    }

    /** One clock of TCK with TMS at the given level. */
    public TapState step(boolean tms) {
        return tms ? high() : low();
    }

    /** Whether the controller stays put when TMS is held one way. */
    public boolean isStable() {
        return this == TEST_LOGIC_RESET || this == RUN_TEST_IDLE || this == PAUSE_DR || this == PAUSE_IR;
    }

    /** Whether this is one of the two states that move data. */
    public boolean isShiftState() {
        return this == SHIFT_DR || this == SHIFT_IR;
    }

    private TapState low() {
        return switch (this) {
            case TEST_LOGIC_RESET, RUN_TEST_IDLE, UPDATE_DR, UPDATE_IR -> RUN_TEST_IDLE;
            case SELECT_DR -> CAPTURE_DR;
            case CAPTURE_DR, SHIFT_DR, EXIT2_DR -> SHIFT_DR;
            case EXIT1_DR, PAUSE_DR -> PAUSE_DR;
            case SELECT_IR -> CAPTURE_IR;
            case CAPTURE_IR, SHIFT_IR, EXIT2_IR -> SHIFT_IR;
            case EXIT1_IR, PAUSE_IR -> PAUSE_IR;
        };
    }

    private TapState high() {
        return switch (this) {
            case TEST_LOGIC_RESET, SELECT_IR -> TEST_LOGIC_RESET;
            case RUN_TEST_IDLE, UPDATE_DR, UPDATE_IR -> SELECT_DR;
            case SELECT_DR -> SELECT_IR;
            case CAPTURE_DR, SHIFT_DR -> EXIT1_DR;
            case EXIT1_DR, EXIT2_DR -> UPDATE_DR;
            case PAUSE_DR -> EXIT2_DR;
            case CAPTURE_IR, SHIFT_IR -> EXIT1_IR;
            case EXIT1_IR, EXIT2_IR -> UPDATE_IR;
            case PAUSE_IR -> EXIT2_IR;
        };
    }

    @Override
    public String toString() {
        return value;
    }
}
