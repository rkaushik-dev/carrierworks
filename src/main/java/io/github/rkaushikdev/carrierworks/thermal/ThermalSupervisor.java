package io.github.rkaushikdev.carrierworks.thermal;

import io.github.rkaushikdev.carrierworks.BenchLocation;
import io.github.rkaushikdev.carrierworks.BoardRegistry;
import io.github.rkaushikdev.carrierworks.LeaseRegistry;
import io.github.rkaushikdev.carrierworks.Locations;
import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.inventory.Board;
import io.github.rkaushikdev.carrierworks.inventory.BoardStatus;
import io.github.rkaushikdev.carrierworks.leases.Lease;
import io.github.rkaushikdev.carrierworks.power.PowerController;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The only thing in the bench that acts on temperature.
 *
 * <p>Everything below this decides; this does. It runs on a tick, folds the samples into alarm
 * states, and turns the states that matter into actions: a board over its shutdown threshold loses
 * its power and its lease, a board that has gone quiet while somebody is using it is treated as
 * unsafe rather than as fine, and a board that is merely warm produces a warning naming the boards
 * downwind of it, because those are the ones that will be next.
 *
 * <p>Two decisions here are worth defending.
 *
 * <p>Shutdown does not ask the lease holder. There is no priority high enough to justify cooking a
 * part, and a job that loses its board to a thermal shutdown gets a clear reason rather than a
 * silent failure twenty minutes later.
 *
 * <p>A stale board that nobody holds is left alone. It is almost certainly a board that has been
 * powered off, and taking action on every idle board would fill the log with noise that hides the
 * one case that matters.
 */
public final class ThermalSupervisor {

    /** How long a board in use may stay quiet before it is shut down, unless told otherwise. */
    public static final Duration DEFAULT_UNKNOWN_GRACE = Duration.ofMinutes(5);

    private static final String ACTOR = "thermal";
    private static final String RELEASE_REASON = "thermal shutdown";

    private final BoardRegistry book;
    private final AlarmBook alarms;
    private final LeaseRegistry leases;
    private final Optional<PowerController> power;
    private final Duration unknownGrace;
    private final int downwindReach;

    /** A supervisor with no outlets to switch, which still faults and releases what it shuts down. */
    public ThermalSupervisor(BoardRegistry book, AlarmBook alarms, LeaseRegistry leases) {
        this(book, alarms, leases, Optional.empty(), DEFAULT_UNKNOWN_GRACE, Locations.DEFAULT_REACH);
    }

    /** A supervisor that powers boards off through this controller when it shuts them down. */
    public ThermalSupervisor(BoardRegistry book, AlarmBook alarms, LeaseRegistry leases, PowerController power) {
        this(book, alarms, leases, Optional.of(power), DEFAULT_UNKNOWN_GRACE, Locations.DEFAULT_REACH);
    }

    /**
     * A supervisor with every setting given.
     *
     * @param unknownGrace how long a board in use may stay quiet before it is shut down
     * @param downwindReach how many shelves above a hot board count as downwind of it
     */
    public ThermalSupervisor(
            BoardRegistry book,
            AlarmBook alarms,
            LeaseRegistry leases,
            Optional<PowerController> power,
            Duration unknownGrace,
            int downwindReach) {
        this.book = book;
        this.alarms = alarms;
        this.leases = leases;
        this.power = power;
        this.unknownGrace = unknownGrace;
        this.downwindReach = downwindReach;
    }

    /** The boards it watches and takes out of service. */
    public BoardRegistry book() {
        return book;
    }

    /** The alarm states it folds the samples into. */
    public AlarmBook alarms() {
        return alarms;
    }

    /** The leases it gives back on a shutdown. */
    public LeaseRegistry leases() {
        return leases;
    }

    /** The outlets it switches off on a shutdown, if it has any. */
    public Optional<PowerController> power() {
        return power;
    }

    /** How long a board in use may stay quiet before it is shut down. */
    public Duration unknownGrace() {
        return unknownGrace;
    }

    /** How many shelves above a hot board count as downwind of it. */
    public int downwindReach() {
        return downwindReach;
    }

    /** One pass: update every board's state and act on what it says, in tag order. */
    public List<SupervisorAction> tick(ThermalLog log, Instant now) {
        List<SupervisorAction> actions = new ArrayList<>();
        for (Board board : book.all()) {
            ThermalStatus status = alarms.update(board.tag(), log, now);
            act(status, now).ifPresent(actions::add);
        }
        return List.copyOf(actions);
    }

    /** The board in the worst state, for a dashboard, if anything is being watched. */
    public Optional<ThermalStatus> worst() {
        return alarms.worstFirst().stream().findFirst();
    }

    /** {@code 1 critical, 1 normal}, or {@code nothing being watched}. */
    public String summary() {
        List<String> states = alarms.worstFirst().stream()
                .map(status -> status.state().value())
                .toList();
        return Records.describeTally(Records.tally(states), "nothing being watched");
    }

    private Optional<SupervisorAction> act(ThermalStatus status, Instant now) {
        String board = status.board();
        return switch (status.state()) {
            case SHUTDOWN -> Optional.of(shutDown(board, now, "die over the shutdown threshold"));
            case UNKNOWN -> quiet(status, now);
            case CRITICAL ->
                Optional.of(
                        new SupervisorAction(board, SupervisorAction.Kind.WARN, status.describe(), downwind(board)));
            case WARN -> Optional.of(new SupervisorAction(board, SupervisorAction.Kind.NOTE, status.describe()));
            case NORMAL -> Optional.empty();
        };
    }

    /** A board that has stopped reporting: left alone when idle, watched, then shut down. */
    private Optional<SupervisorAction> quiet(ThermalStatus status, Instant now) {
        String board = status.board();
        if (leases.forBoard(board, now).isEmpty()) {
            return Optional.empty();
        }
        if (status.heldFor(now).compareTo(unknownGrace) < 0) {
            return Optional.of(
                    new SupervisorAction(board, SupervisorAction.Kind.WATCH, "no readings while the board is in use"));
        }
        return Optional.of(shutDown(board, now, "stopped reporting while in use"));
    }

    /** Power the board off, give the lease back, and take it out of service. */
    private SupervisorAction shutDown(String board, Instant now, String reason) {
        leases.forBoard(board, now).map(Lease::id).ifPresent(id -> leases.release(id, now, RELEASE_REASON));
        if (power.isPresent() && book.has(board)) {
            power.get().powerOff(book.board(board));
        }
        if (book.state(board).state() != BoardStatus.FAULTED) {
            book.moveTo(board, BoardStatus.FAULTED, now, reason, ACTOR);
        }
        return new SupervisorAction(board, SupervisorAction.Kind.SHUTDOWN, reason, downwind(board));
    }

    /** Which boards sit in this one's warm air, nearest first. */
    private List<String> downwind(String board) {
        if (!book.has(board)) {
            return List.of();
        }
        BenchLocation source = book.board(board).location();
        Map<String, BenchLocation> others = new LinkedHashMap<>();
        for (Board other : book.all()) {
            if (!other.tag().equals(board)) {
                others.put(other.tag(), other.location());
            }
        }
        return Locations.downwindOf(source, others, downwindReach);
    }
}
