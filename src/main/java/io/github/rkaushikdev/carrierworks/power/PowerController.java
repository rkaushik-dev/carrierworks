package io.github.rkaushikdev.carrierworks.power;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.PduChannel;
import io.github.rkaushikdev.carrierworks.PowerFault;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.inventory.Board;

/**
 * Board level power, on top of whatever driver the rack has.
 *
 * <p>Power cycling is the operation worth being careful about. Off then on with no wait in between
 * is not a power cycle: the bulk capacitance on a carrier holds the rails up for tens of
 * milliseconds and the device never sees a reset. The controller enforces a minimum off time rather
 * than trusting the caller, because the caller is usually a retry loop that has no idea why the last
 * attempt failed.
 *
 * <p>Every call needs the board to be on a switched outlet, and any of them can raise the driver's
 * {@link PowerFault} when the unit does not answer.
 */
public final class PowerController {

    /** The shortest off time that resets a board; below it the bulk capacitance keeps the rails up. */
    public static final int MINIMUM_OFF_MS = 250;

    /** How far past what its cards account for a board may draw before it counts as over budget. */
    public static final int DEFAULT_HEADROOM_MW = 2000;

    /** The rack feeds carriers at twelve volts. */
    private static final int FEED_VOLTS = 12;

    private final PduDriver driver;
    private final int minimumOffMs;

    /** A controller holding boards off for the shortest time that resets them. */
    public PowerController(PduDriver driver) {
        this(driver, MINIMUM_OFF_MS);
    }

    /**
     * A controller holding boards off for at least {@code minimumOffMs} on a cycle.
     *
     * @throws ValidationError when that is shorter than {@value #MINIMUM_OFF_MS} milliseconds
     */
    public PowerController(PduDriver driver, int minimumOffMs) {
        if (minimumOffMs < MINIMUM_OFF_MS) {
            throw new ValidationError(
                    "that off time is too short to reset a board",
                    "minimum_off_ms",
                    Details.of("given", minimumOffMs, "floor", MINIMUM_OFF_MS));
        }
        this.driver = driver;
        this.minimumOffMs = minimumOffMs;
    }

    /** The driver the outlets are switched through. */
    public PduDriver driver() {
        return driver;
    }

    /** The shortest a cycle holds a board off. */
    public int minimumOffMs() {
        return minimumOffMs;
    }

    /** Whether a board's outlet is on. */
    public boolean isOn(Board board) {
        return driver.isOn(channel(board));
    }

    /** Switch a board on, and say whether that changed anything. */
    public boolean powerOn(Board board) {
        PduChannel channel = channel(board);
        if (driver.isOn(channel)) {
            return false;
        }
        driver.switchOutlet(channel, true);
        return true;
    }

    /** Switch a board off, and say whether that changed anything. */
    public boolean powerOff(Board board) {
        PduChannel channel = channel(board);
        if (!driver.isOn(channel)) {
            return false;
        }
        driver.switchOutlet(channel, false);
        return true;
    }

    /** {@link #cycle(Board, int)} for the minimum off time. */
    public int cycle(Board board) {
        return cycle(board, minimumOffMs);
    }

    /**
     * Off, then on. Returns the off time the cycle is owed, which is {@code offMs} clamped up to the
     * minimum.
     *
     * <p>The wait is clamped rather than refused, because every caller of this is already handling a
     * failure and does not need another one.
     */
    public int cycle(Board board, int offMs) {
        int wait = Math.max(offMs, minimumOffMs);
        PduChannel channel = channel(board);
        driver.switchOutlet(channel, false);
        driver.switchOutlet(channel, true);
        return wait;
    }

    /** What a board is drawing at the wall, in milliamps. */
    public int drawMa(Board board) {
        return driver.currentMa(channel(board));
    }

    /** {@link #overBudget(Board, int)} with the default headroom. */
    public boolean overBudget(Board board) {
        return overBudget(board, DEFAULT_HEADROOM_MW);
    }

    /**
     * Whether the board is pulling more than its cards should account for, plus some headroom.
     *
     * <p>A rough check on purpose. The outlet reads whole board current at the wall, so this catches
     * a short or a card that has started to fail, not a rail that is ten percent high.
     */
    public boolean overBudget(Board board, int headroomMw) {
        int budget = board.cardPowerMw() + headroomMw;
        int drawn = drawMa(board) * FEED_VOLTS;
        return drawn > budget;
    }

    private static PduChannel channel(Board board) {
        return board.pdu()
                .orElseThrow(() -> new ValidationError(
                        "that board is not on a switched outlet", "pdu", Details.of("board", board.tag())));
    }
}
