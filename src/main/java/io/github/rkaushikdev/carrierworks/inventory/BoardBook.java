package io.github.rkaushikdev.carrierworks.inventory;

import io.github.rkaushikdev.carrierworks.AssetTag;
import io.github.rkaushikdev.carrierworks.BoardRegistry;
import io.github.rkaushikdev.carrierworks.ConfigurationError;
import io.github.rkaushikdev.carrierworks.Details;
import io.github.rkaushikdev.carrierworks.NotFound;
import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.RequirementSet;
import io.github.rkaushikdev.carrierworks.ValidationError;
import io.github.rkaushikdev.carrierworks.jtag.AdapterUnit;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Every board on the bench, with the uniqueness rules the room enforces.
 *
 * <p>Four things cannot be shared and the bench has been wrong about all four at some point. Two
 * boards cannot have one asset tag, because the tag is how a person and the database agree on which
 * board they mean. Two boards cannot be in one slot, because there is only one slot. One cable
 * cannot be on two boards, which is the mistake that happens when a board is moved and the old row
 * is not updated, and the symptom is a job programming the wrong board. And one outlet cannot feed
 * two boards, because power cycling one would take the other down in the middle of whatever it was
 * doing.
 *
 * <p>The book holds state beside each board rather than inside it. A board is a physical fact that
 * changes when somebody picks up a screwdriver; its state changes many times an hour. Keeping them
 * apart means a state change does not rewrite the record that says what is fitted.
 *
 * <p>Listings come back sorted by the tag's text, so {@code bx-10} comes before {@code bx-9}.
 */
public final class BoardBook implements BoardRegistry {

    /** The reason a board added out of service carries when nobody gave one. */
    public static final String ADDED_REASON = "added to the bench";

    private final Map<String, Board> boards = new LinkedHashMap<>();
    private final Map<String, BoardState> states = new HashMap<>();
    private final Optional<Instant> epoch;

    /** A book whose boards must each be added with the time they arrived. */
    public BoardBook() {
        this.epoch = Optional.empty();
    }

    /** A book whose boards arrive at {@code when} unless they are added with a time of their own. */
    public BoardBook(Instant when) {
        this.epoch = Optional.of(when);
    }

    /** {@link #add(Board, Instant, BoardStatus, String)} as available, at the book's own time. */
    public Board add(Board board) {
        return add(board, BoardStatus.AVAILABLE, "");
    }

    /** {@link #add(Board, Instant, BoardStatus, String)} at the book's own time. */
    public Board add(Board board, BoardStatus state, String reason) {
        return admit(board, epoch, state, reason);
    }

    /**
     * Put a board in the book, refusing anything that collides.
     *
     * <p>A board added in a state that needs a reason and given none is recorded as {@value
     * #ADDED_REASON}.
     *
     * @throws ConfigurationError when the tag is already in the book, or the board shares a slot, a
     *     cable or an outlet with one that is, or there is no time to say it arrived at
     */
    public Board add(Board board, Instant when, BoardStatus state, String reason) {
        return admit(board, Optional.of(when), state, reason);
    }

    private Board admit(Board board, Optional<Instant> when, BoardStatus state, String reason) {
        String tag = board.tag();
        if (boards.containsKey(tag)) {
            throw new ConfigurationError("that asset tag is already on the bench", Details.of("board", tag));
        }
        checkFree(board, "");
        Instant moment =
                when.orElseThrow(() -> new ConfigurationError("a board arrives at a time", Details.of("board", tag)));
        String settled = reason.strip();
        if (settled.isEmpty() && state.needsReason()) {
            settled = ADDED_REASON;
        }
        BoardState arrived = new BoardState(state, moment, settled);
        boards.put(tag, board);
        states.put(tag, arrived);
        return board;
    }

    private void checkFree(Board board, String ignoring) {
        for (Board other : boards.values()) {
            if (other.tag().equals(ignoring)) {
                continue;
            }
            if (other.location().equals(board.location())) {
                throw new ConfigurationError(
                        "another board is already in that slot",
                        Details.of(
                                "board",
                                board.tag(),
                                "holder",
                                other.tag(),
                                "location",
                                board.location().toString()));
            }
            Optional<String> cable = board.adapter().map(AdapterUnit::serial);
            if (cable.isPresent() && cable.equals(other.adapter().map(AdapterUnit::serial))) {
                throw new ConfigurationError(
                        "that cable is already on another board",
                        Details.of("board", board.tag(), "holder", other.tag(), "serial", cable.get()));
            }
            if (board.pdu().isPresent() && other.pdu().equals(board.pdu())) {
                throw new ConfigurationError(
                        "that outlet already feeds another board",
                        Details.of(
                                "board",
                                board.tag(),
                                "holder",
                                other.tag(),
                                "outlet",
                                board.pdu().get().toString()));
            }
        }
    }

    /**
     * Write a changed board back, keeping its state.
     *
     * @throws NotFound when the board is not in the book
     * @throws ConfigurationError when the change collides with another board
     */
    public Board replace(Board board) {
        String tag = board.tag();
        if (!boards.containsKey(tag)) {
            throw new NotFound("board", tag);
        }
        checkFree(board, tag);
        boards.put(tag, board);
        return board;
    }

    /** Take a board out of the book, and its state with it. */
    public Board remove(String tag) {
        String key = AssetTag.parse(tag).toString();
        if (!boards.containsKey(key)) {
            throw new NotFound("board", key);
        }
        states.remove(key);
        return boards.remove(key);
    }

    /** One board by tag, however the tag was written, or a not found. */
    @Override
    public Board board(String tag) {
        String key = AssetTag.parse(tag).toString();
        Board found = boards.get(key);
        if (found == null) {
            throw new NotFound("board", key);
        }
        return found;
    }

    /** Whether a board with this tag is in the book. */
    @Override
    public boolean has(String tag) {
        return boards.containsKey(AssetTag.parse(tag).toString());
    }

    /** Where one board is now, or a not found. */
    @Override
    public BoardState state(String tag) {
        return states.get(board(tag).tag());
    }

    /** {@link #moveTo(String, BoardStatus, Instant, String, String)} with no reason and nobody named. */
    @Override
    public BoardState moveTo(String tag, BoardStatus target, Instant when) {
        return moveTo(tag, target, when, "", "");
    }

    /** {@link #moveTo(String, BoardStatus, Instant, String, String)} with nobody named. */
    @Override
    public BoardState moveTo(String tag, BoardStatus target, Instant when, String reason) {
        return moveTo(tag, target, when, reason, "");
    }

    /**
     * Move one board's state, through the state machine's own rules.
     *
     * @throws ValidationError when the state machine refuses the move
     */
    @Override
    public BoardState moveTo(String tag, BoardStatus target, Instant when, String reason, String actor) {
        String key = board(tag).tag();
        BoardState moved = states.get(key).move(target, when, reason, actor);
        states.put(key, moved);
        return moved;
    }

    /** Every board, sorted by tag. */
    @Override
    public List<Board> all() {
        return tags().stream().map(boards::get).toList();
    }

    /** Every tag, sorted. */
    public List<String> tags() {
        return boards.keySet().stream().sorted().toList();
    }

    /** How many boards are in the book. */
    public int size() {
        return boards.size();
    }

    /** Every board in one state, sorted by tag. */
    @Override
    public List<Board> inState(BoardStatus state) {
        return all().stream()
                .filter(board -> states.get(board.tag()).state() == state)
                .toList();
    }

    /** Every board a job could be scheduled onto right now, sorted by tag. */
    public List<Board> available() {
        return all().stream().filter(board -> states.get(board.tag()).usable()).toList();
    }

    /** Every board in one rack, sorted by tag. */
    public List<Board> inRack(String rack) {
        String wanted = rack.strip().toLowerCase(Locale.ROOT);
        return all().stream()
                .filter(board -> board.location().rack().equals(wanted))
                .toList();
    }

    /** Every board carrying one device, sorted by tag. */
    public List<Board> withDevice(String device) {
        String wanted = device.strip().toLowerCase(Locale.ROOT);
        return all().stream()
                .filter(board -> board.device().device().equals(wanted))
                .toList();
    }

    /** Every available board that could take a job asking for this. */
    public List<Board> matching(RequirementSet wanted) {
        return matching(wanted, true);
    }

    /** Every board that could take a job asking for this, busy or out of service ones too if asked. */
    public List<Board> matching(RequirementSet wanted, boolean onlyAvailable) {
        List<Board> pool = onlyAvailable ? available() : all();
        return pool.stream()
                .filter(board -> BoardProfile.matches(board, wanted))
                .toList();
    }

    /** Where every board is racked, by tag, in tag order. */
    public Map<String, String> locations() {
        Map<String, String> found = new LinkedHashMap<>();
        all().forEach(board -> found.put(board.tag(), board.location().toString()));
        return Collections.unmodifiableMap(found);
    }

    /** The short form of every board, sorted by tag. */
    public List<BoardSummary> summaries() {
        return all().stream().map(BoardSummary::of).toList();
    }

    /** {@code 2 boards: 1 available, 1 faulted}, or {@code no boards}. */
    public String describe() {
        if (boards.isEmpty()) {
            return "no boards";
        }
        Map<String, Integer> counts = Records.tally(all().stream()
                .map(board -> states.get(board.tag()).state().value())
                .toList());
        return size() + " boards: " + Records.describeTally(counts);
    }
}
