package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.Ids;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What state a board is in, and which moves are allowed from it.
 *
 * <p>The states are the ones the lab already used on a whiteboard, which is why there are seven of
 * them rather than three. The transitions are written as a table because the interesting question
 * is nearly always "why can it not go there", and a table can answer that; a pile of conditions
 * cannot.
 *
 * <p>Two rules are load bearing. Retired is terminal: a board that has been stripped for parts
 * cannot come back because the parts are on somebody else's board now. And quarantine is not a
 * fault. A faulted board has failed once and may well be fine; a quarantined one has failed enough
 * times that the bench has stopped believing it, and only a person with a soldering iron gets to end
 * that. So quarantine cannot go straight back to available, which is exactly the shortcut somebody
 * takes at eleven at night when they need a board.
 */
public enum BoardStatus {
    AVAILABLE("available"),
    LEASED("leased"),
    PROGRAMMING("programming"),
    FAULTED("faulted"),
    QUARANTINED("quarantined"),
    MAINTENANCE("maintenance"),
    RETIRED("retired");

    private final String value;

    BoardStatus(String value) {
        this.value = value;
    }

    /** The name the state is stored, printed and asked for under. */
    public String value() {
        return value;
    }

    /** The state with this name, however it was written. */
    public static BoardStatus parse(String text) {
        String name = Ids.slug(text);
        return Arrays.stream(values())
                .filter(status -> status.value.equals(name))
                .findFirst()
                .orElseThrow(() -> new ValidationError("no such board state", "state", Details.of("given", text)));
    }

    /** Everywhere a board in this state may go, in the order the states are listed. */
    public List<BoardStatus> moves() {
        Set<BoardStatus> allowed = allowed(this);
        return Arrays.stream(values()).filter(allowed::contains).toList();
    }

    /** Whether a board in this state may go to that one. */
    public boolean canMoveTo(BoardStatus target) {
        return allowed(this).contains(target);
    }

    /** Whether a board cannot be put in this state without somebody saying why. */
    public boolean needsReason() {
        return this == FAULTED || this == QUARANTINED || this == RETIRED || this == MAINTENANCE;
    }

    /** Whether a job could be scheduled onto a board in this state. */
    public boolean usable() {
        return this == AVAILABLE;
    }

    /** Whether somebody is using the board. */
    public boolean busy() {
        return this == LEASED || this == PROGRAMMING;
    }

    /** Whether the board is out of service. */
    public boolean outOfService() {
        return this == FAULTED || this == QUARANTINED || this == MAINTENANCE || this == RETIRED;
    }

    /** Whether there is nowhere left to go. */
    public boolean terminal() {
        return allowed(this).isEmpty();
    }

    private static Set<BoardStatus> allowed(BoardStatus from) {
        return switch (from) {
            case AVAILABLE -> EnumSet.of(LEASED, FAULTED, MAINTENANCE, RETIRED);
            case LEASED -> EnumSet.of(PROGRAMMING, AVAILABLE, FAULTED, MAINTENANCE);
            case PROGRAMMING -> EnumSet.of(LEASED, FAULTED);
            case FAULTED -> EnumSet.of(AVAILABLE, QUARANTINED, MAINTENANCE, RETIRED);
            case QUARANTINED -> EnumSet.of(MAINTENANCE, RETIRED);
            case MAINTENANCE -> EnumSet.of(AVAILABLE, QUARANTINED, RETIRED);
            case RETIRED -> EnumSet.noneOf(BoardStatus.class);
        };
    }

    @Override
    public String toString() {
        return value;
    }
}
