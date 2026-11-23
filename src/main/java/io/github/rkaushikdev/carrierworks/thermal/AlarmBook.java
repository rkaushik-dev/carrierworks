package io.github.rkaushikdev.carrierworks.thermal;

import io.github.rkaushikdev.carrierworks.Records;
import io.github.rkaushikdev.carrierworks.ThermalLimits;
import io.github.rkaushikdev.carrierworks.ThermalState;
import io.github.rkaushikdev.carrierworks.ValidationError;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * The thermal state of every board the bench is watching.
 *
 * <p>The state machine itself lives with {@link ThermalLimits}, because the hysteresis rule is a
 * property of thresholds rather than of the bench. What is here is the bookkeeping around it: which
 * board is in which state, since when, and what changed at the last update.
 *
 * <p>Unknown is a state, not a missing value. A board that has stopped reporting is in a state the
 * bench cannot reason about, and the honest thing is to say so and let the supervisor decide.
 * Treating it as normal is worse than treating it as critical, and both are worse than saying it
 * out loud.
 */
public final class AlarmBook {

    /** How much recent history the state is decided on, unless the book is told otherwise. */
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

    /** How long a board may go unheard before it is unknown, unless the book is told otherwise. */
    public static final Duration DEFAULT_STALE_AFTER = Duration.ofMinutes(3);

    /**
     * How fast a die may heat, in millidegrees a minute, before a board still under its warning
     * line is warned about anyway, unless the book is told otherwise.
     */
    public static final int DEFAULT_RISE_LIMIT_MC = 10_000;

    /** How much history the rate of rise is read over: enough polls for a slope to mean something. */
    public static final Duration TREND_WINDOW = Duration.ofMinutes(3);

    private static final Comparator<ThermalStatus> WORST_FIRST =
            Comparator.comparingInt(ThermalStatus::rank).reversed().thenComparing(ThermalStatus::board);

    private final ThermalLimits limits;
    private final Duration window;
    private final Duration staleAfter;
    private final int riseLimitMc;
    private final Map<String, ThermalStatus> statuses = new TreeMap<>();
    private final Map<String, ThermalLimits> boardLimits = new TreeMap<>();

    /** A book deciding on the last minute of readings and calling a board unknown after three. */
    public AlarmBook(ThermalLimits limits) {
        this(limits, DEFAULT_WINDOW, DEFAULT_STALE_AFTER);
    }

    /** A book deciding on {@code window} of readings and calling a board unknown after {@code staleAfter}. */
    public AlarmBook(ThermalLimits limits, Duration window, Duration staleAfter) {
        this(limits, window, staleAfter, DEFAULT_RISE_LIMIT_MC);
    }

    /**
     * A book that also warns about a cool board heating faster than {@code riseLimitMc}
     * millidegrees a minute.
     *
     * <p>A die that climbs ten degrees a minute from fifty reaches shutdown in four, and the poll
     * after it crosses the warning line is too late to do anything gentle about it. A stalled fan
     * or a heatsink that has come unclipped looks exactly like that.
     */
    public AlarmBook(ThermalLimits limits, Duration window, Duration staleAfter, int riseLimitMc) {
        if (!window.isPositive() || !staleAfter.isPositive()) {
            throw new ValidationError("both windows have some length", "alarms");
        }
        if (riseLimitMc <= 0) {
            throw new ValidationError("a rise limit is a positive rate", "rise_limit_mc");
        }
        this.limits = limits;
        this.window = window;
        this.staleAfter = staleAfter;
        this.riseLimitMc = riseLimitMc;
    }

    /** How fast a cool board may heat, in millidegrees a minute, before it is warned about. */
    public int riseLimitMc() {
        return riseLimitMc;
    }

    /** The thresholds a board is judged against unless it has its own. */
    public ThermalLimits limits() {
        return limits;
    }

    /**
     * Judge one board against its own thresholds from now on, which is what a board whose part is
     * rated hotter or cooler than the rest of the bench needs.
     */
    public AlarmBook limit(String board, ThermalLimits own) {
        boardLimits.put(Records.boardKey(board), own);
        return this;
    }

    /** The thresholds this board is judged against. */
    public ThermalLimits limitsFor(String board) {
        return boardLimits.getOrDefault(Records.boardKey(board), limits);
    }

    /** How much recent history the state is decided on. */
    public Duration window() {
        return window;
    }

    /** How long a board may go unheard before it is unknown. */
    public Duration staleAfter() {
        return staleAfter;
    }

    /** Where a board stands, if the book is watching it. */
    public Optional<ThermalStatus> status(String board) {
        return Optional.ofNullable(statuses.get(Records.boardKey(board)));
    }

    /**
     * Fold the latest readings into this board's state.
     *
     * <p>A stale board goes to unknown, keeping the time it first went quiet. Otherwise the average
     * over the window moves the board along the escalation with the hysteresis applied, starting
     * from normal when the board was unknown or new. A board that would be normal is warned about
     * when its die is heating faster than the rise limit. The since time only moves when the state
     * does.
     */
    public ThermalStatus update(String board, ThermalLog log, Instant now) {
        String tag = Records.boardKey(board);
        Optional<ThermalStatus> held = Optional.ofNullable(statuses.get(tag));
        ThermalStatus status =
                log.stale(tag, now, staleAfter) ? unheard(tag, held, log, now) : judged(tag, held, log, now);
        statuses.put(tag, status);
        return status;
    }

    private static ThermalStatus unheard(String tag, Optional<ThermalStatus> held, ThermalLog log, Instant now) {
        OptionalInt last = log.latest(tag)
                .map(sample -> OptionalInt.of(sample.millidegrees()))
                .orElse(OptionalInt.empty());
        return new ThermalStatus(tag, ThermalState.UNKNOWN, since(held, ThermalState.UNKNOWN, now), last, true);
    }

    private ThermalStatus judged(String tag, Optional<ThermalStatus> held, ThermalLog log, Instant now) {
        // The newest reading can be fresh and still fall outside the window; it stands in then.
        int reading = log.average(tag, now, window)
                .orElseGet(() -> log.latest(tag).orElseThrow().millidegrees());
        ThermalState previous = held.map(ThermalStatus::state)
                .filter(state -> state != ThermalState.UNKNOWN)
                .orElse(ThermalState.NORMAL);
        ThermalState state = limitsFor(tag).nextState(previous, reading);
        if (state == ThermalState.NORMAL
                && log.risePerMinute(tag, now, TREND_WINDOW).orElse(0) > riseLimitMc) {
            state = ThermalState.WARN;
        }
        return new ThermalStatus(tag, state, since(held, state, now), OptionalInt.of(reading), false);
    }

    /** When the board entered a state: kept from before if it was already there, otherwise now. */
    private static Instant since(Optional<ThermalStatus> held, ThermalState state, Instant now) {
        return held.filter(prior -> prior.state() == state)
                .map(ThermalStatus::since)
                .orElse(now);
    }

    /** Update every board the log knows about, and hand back every status, worst first. */
    public List<ThermalStatus> updateAll(ThermalLog log, Instant now) {
        for (String board : log.boards()) {
            update(board, log, now);
        }
        return worstFirst();
    }

    /** Every status, worst first, then by tag. */
    public List<ThermalStatus> worstFirst() {
        return statuses.values().stream().sorted(WORST_FIRST).toList();
    }

    /** The statuses a person should be told about, worst first. */
    public List<ThermalStatus> alarming() {
        return worstFirst().stream().filter(ThermalStatus::alarming).toList();
    }

    /** The boards in one state, worst first. */
    public List<ThermalStatus> inState(ThermalState state) {
        return worstFirst().stream().filter(status -> status.state() == state).toList();
    }

    /** Stop watching a board, which is what releasing its lease does. */
    public void forget(String board) {
        statuses.remove(Records.boardKey(board));
    }

    /** Every board being watched, in tag order. */
    public List<String> boards() {
        return List.copyOf(statuses.keySet());
    }

    /** How many boards are being watched. */
    public int size() {
        return statuses.size();
    }
}
